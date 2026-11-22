use std::{
    collections::HashMap,
    panic::{catch_unwind, AssertUnwindSafe},
    str::FromStr,
    sync::{Arc, Mutex, OnceLock},
    sync::atomic::{AtomicBool, AtomicU64, Ordering},
};

use ed25519_dalek::{Signature, VerifyingKey};
use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jboolean, jbyteArray, jlong, jstring},
    JNIEnv,
};
use serde_json::json;
use sha2::{Digest, Sha256};
use solana_address::Address;
use solana_pubkey::Pubkey;
use spr_client::{
    compile_message, ComputeBudgetConfig, IndexerPollConfig, IndexerRpcConfig,
    ProofDataSource, ProverClient, Rpc, SignedPrivateTransaction, SolanaRpc,
    SprClient, SprIndexer, Submission, UtxoScan,
};
use spr_client::prover::tee::{SignedTeePolicy, TeeNetwork};
use spr_interface::{pda, PROGRAM_ID_PUBKEY};
use spr_instruction::instruction::{
    AssetDeposit, Deposit, DepositAsset, TransactInterfaceTransferAccounts,
};
use spr_keypair::{
    derivation::{ed25519_derivation_message, expand_roles},
    pubkey::PublicKey,
    shielded::ShieldedAddress,
    Curve, NullifierKey,
};
use spr_transaction::{
    asset::{AssetRegistry, SOL_MINT},
    instructions::transact::ConfidentialTransaction,
    keys::LocalShieldedKeys,
    select_spend,
};
use zeroize::{Zeroize, Zeroizing};

#[derive(Clone, Copy)]
enum Operation {
    DepositSol,
    TransferSol,
    WithdrawSol,
}

impl Operation {
    fn as_str(self) -> &'static str {
        match self {
            Self::DepositSol => "deposit_sol",
            Self::TransferSol => "transfer_sol",
            Self::WithdrawSol => "withdraw_sol",
        }
    }
}

struct Prepared {
    operation: Operation,
    amount: u64,
    counterparty: String,
    message: Vec<u8>,
    last_valid_block_height: u64,
}

impl Drop for Prepared {
    fn drop(&mut self) {
        // Prepared messages carry encrypted note data and should not remain in
        // freed heap pages after approval, replacement, or session close.
        self.message.zeroize();
        self.counterparty.zeroize();
        self.amount = 0;
    }
}

struct WalletContext {
    public_key: [u8; 32],
    shielded_address: ShieldedAddress,
    nullifier: NullifierKey,
    keys: LocalShieldedKeys,
    prepared: Option<Prepared>,
}

struct WalletSession {
    context: Mutex<WalletContext>,
    closed: AtomicBool,
}

impl WalletSession {
    fn new(context: WalletContext) -> Self {
        Self { context: Mutex::new(context), closed: AtomicBool::new(false) }
    }
}

trait SessionOutput {
    fn discard(&mut self);
}

impl SessionOutput for String {
    fn discard(&mut self) { self.zeroize(); }
}
impl SessionOutput for Vec<u8> {
    fn discard(&mut self) { self.zeroize(); }
}
impl SessionOutput for bool {
    fn discard(&mut self) { *self = false; }
}
impl SessionOutput for () {
    fn discard(&mut self) {}
}

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);
static WALLETS: OnceLock<Mutex<HashMap<i64, Arc<WalletSession>>>> = OnceLock::new();

fn wallets() -> &'static Mutex<HashMap<i64, Arc<WalletSession>>> {
    WALLETS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn lock_wallets() -> Result<std::sync::MutexGuard<'static, HashMap<i64, Arc<WalletSession>>>, String> {
    wallets().lock().map_err(|_| "privacy state unavailable".to_owned())
}

fn helius_service_url(rpc_url: &str) -> Result<String, String> {
    let mut url = url::Url::parse(rpc_url).map_err(|_| "RPC principal inválido".to_owned())?;
    let host = url.host_str().unwrap_or_default().to_ascii_lowercase();
    if url.scheme() != "https" || !(host == "helius-rpc.com" || host.ends_with(".helius-rpc.com")) {
        return Err("Configure o RPC principal com um endpoint Helius HTTPS".to_owned());
    }
    let has_key = url.query_pairs().any(|(key, value)| key == "api-key" && !value.is_empty());
    if !has_key {
        return Err("O endpoint Helius precisa conter api-key".to_owned());
    }
    let path = url.path().trim_end_matches('/');
    if !path.ends_with("/v1/spr") {
        url.set_path(&format!("{path}/v1/spr"));
    }
    Ok(url.to_string())
}

fn make_client(rpc_url: &str) -> Result<SprClient<SolanaRpc, SprIndexer>, String> {
    let service_url = helius_service_url(rpc_url)?;
    let rpc = SolanaRpc::new(rpc_url.to_owned());
    let indexer = SprIndexer::new(service_url.clone());
    let prover = ProverClient::new(service_url)
        .with_signed_tee(SignedTeePolicy::for_network(TeeNetwork::Mainnet));
    Ok(SprClient::new(rpc, indexer, prover)
        .with_compute_unit_limit(spr_client::DEFAULT_TRANSACT_CU_LIMIT)
        .with_proof_data_source(ProofDataSource::Client))
}

fn scan_sol(wallet: &WalletContext, rpc_url: &str) -> Result<(u64, usize), String> {
    let service_url = helius_service_url(rpc_url)?;
    let rpc = SolanaRpc::new(rpc_url.to_owned());
    let indexer = SprIndexer::new(service_url);
    let slot = rpc.get_slot().map_err(|_| "Não foi possível consultar o slot Solana".to_owned())?;
    let config = IndexerRpcConfig {
        poll: IndexerPollConfig::new(30, 250, 1_000),
        require_slot: Some(slot),
    };
    let result = UtxoScan::new(&wallet.keys, &AssetRegistry::default())
        .with_indexer_config(config)
        .fetch(&indexer)
        .map_err(|_| "Falha ao sincronizar o saldo confidencial com o indexador Helius".to_owned())?;
    let balance = result.spendable.balances
        .get_balance(SOL_MINT)
        .map(|asset| asset.amount)
        .unwrap_or(0);
    let count = result.spendable.utxos().count();
    Ok((balance, count))
}

fn save_prepared(
    wallet: &mut WalletContext,
    operation: Operation,
    amount: u64,
    counterparty: String,
    message: solana_message::VersionedMessage,
    last_valid_block_height: u64,
) -> Result<Vec<u8>, String> {
    let v1 = match &message {
        solana_message::VersionedMessage::V1(v1) => v1,
        _ => return Err("O SDK não produziu uma mensagem Solana V1".to_owned()),
    };
    // In Solana messages the first account key is the fee payer. The
    // Default Ring submission requires exactly one signer, and that first
    // key must be the currently PIN-authorized wallet.
    if v1.header.num_required_signatures != 1 ||
        v1.account_keys.first().map(|key| key.to_bytes()) != Some(wallet.public_key)
    {
        return Err("A mensagem não pertence ao único signatário/pagador autorizado da carteira".to_owned());
    }
    let bytes = message.serialize();
    if bytes.is_empty() || bytes.len() + 64 > 4_096 {
        return Err("A mensagem excede o limite do Default Ring".to_owned());
    }
    wallet.prepared = Some(Prepared {
        operation,
        amount,
        counterparty,
        message: bytes.clone(),
        last_valid_block_height,
    });
    Ok(bytes)
}

fn prepare_deposit_sol(
    wallet: &mut WalletContext,
    rpc_url: &str,
    amount: u64,
) -> Result<Vec<u8>, String> {
    if amount == 0 {
        return Err("Informe um valor maior que zero".to_owned());
    }
    let _service = helius_service_url(rpc_url)?;
    let rpc = SolanaRpc::new(rpc_url.to_owned());

    // Match the official SDK/CLI default tree selection (tree id 0). Do not
    // confuse ProtocolConfig.next_tree_id (next creation id) with the active
    // default tree used by wallet deposits.
    let tree = pda::tree(0);
    let tree_address = Address::new_from_array(tree.to_bytes());
    let tree_account = rpc.get_account(tree_address)
        .map_err(|_| "Não foi possível verificar a árvore padrão do Default Ring".to_owned())?
        .ok_or_else(|| "A árvore padrão do Default Ring não está disponível na Mainnet".to_owned())?;
    if tree_account.owner != PROGRAM_ID_PUBKEY {
        return Err("A árvore padrão não pertence ao Shielded Pool oficial".to_owned());
    }
    let (blockhash, last_valid_block_height) = rpc.get_latest_blockhash()
        .map_err(|_| "Não foi possível obter um blockhash atual".to_owned())?;
    let instruction = Deposit {
        tree,
        depositor: Pubkey::new_from_array(wallet.public_key),
        deposits: vec![AssetDeposit {
            asset: DepositAsset::Sol,
            view_tag: wallet.shielded_address.confidential_view_tag()
                .map_err(|_| "Falha ao derivar a tag de visualização".to_owned())?,
            owner: wallet.shielded_address.owner_hash()
                .map_err(|_| "Falha ao derivar a identidade confidencial".to_owned())?,
            amount,
            memo: None,
        }],
    }.instruction().map_err(|_| "Não foi possível construir o depósito Default Ring".to_owned())?;

    // Official Solana V1 compiler from the pinned SDK. The message is returned
    // unsigned; only LockWallet custody may provide its signature.
    let payer = Address::new_from_array(wallet.public_key);
    let message = compile_message(
        &payer,
        &[instruction],
        blockhash,
        ComputeBudgetConfig::for_instruction_count(1),
    ).map_err(|_| "Não foi possível compilar a mensagem de depósito".to_owned())?;
    save_prepared(
        wallet, Operation::DepositSol, amount,
        bs58::encode(wallet.public_key).into_string(),
        message, last_valid_block_height,
    )
}

fn prepare_private_sol(
    wallet: &mut WalletContext,
    rpc_url: &str,
    amount: u64,
    recipient: &str,
    operation: Operation,
) -> Result<Vec<u8>, String> {
    if amount == 0 {
        return Err("Informe um valor maior que zero".to_owned());
    }
    let client = make_client(rpc_url)?;
    let payer = Address::new_from_array(wallet.public_key);
    let slot = client.rpc().get_slot()
        .map_err(|_| "Não foi possível consultar o slot Solana".to_owned())?;
    let scan_config = IndexerRpcConfig {
        poll: IndexerPollConfig::new(30, 250, 1_000),
        require_slot: Some(slot),
    };
    let spendable = UtxoScan::new(&wallet.keys, &AssetRegistry::default())
        .with_indexer_config(scan_config)
        .fetch(client.indexer())
        .map_err(|_| "Não foi possível sincronizar os UTXOs confidenciais".to_owned())?
        .spendable;
    let inputs = select_spend(spendable.utxos(), SOL_MINT, amount)
        .map_err(|_| "Saldo confidencial de SOL insuficiente ou indisponível".to_owned())?;

    let mut transaction = ConfidentialTransaction::new(inputs, payer)
        .map_err(|_| "Não foi possível preparar os UTXOs para gasto".to_owned())?;
    let settlement_transfers: Vec<TransactInterfaceTransferAccounts>;
    let review_counterparty: String;
    match operation {
        Operation::TransferSol => {
            let shielded = ShieldedAddress::from_str(recipient)
                .map_err(|_| "Endereço confidencial de destino inválido".to_owned())?;
            let public_address = shielded.solana_address()
                .map_err(|_| "Endereço confidencial de destino inválido".to_owned())?;
            transaction.transfer_sol(&shielded, amount)
                .map_err(|_| "Não foi possível construir a transferência confidencial".to_owned())?;
            settlement_transfers = Vec::new();
            review_counterparty = public_address.to_string();
        }
        Operation::WithdrawSol => {
            let destination = Address::from_str(recipient)
                .map_err(|_| "Endereço Solana de destino inválido".to_owned())?;
            let settlement = transaction.withdraw_to(SOL_MINT, amount, destination, None)
                .map_err(|_| "Não foi possível construir a retirada pública".to_owned())?;
            settlement_transfers = vec![settlement];
            review_counterparty = destination.to_string();
        }
        Operation::DepositSol => return Err("Operação inválida".to_owned()),
    }

    let proof_inputs = transaction.encrypt(&wallet.keys)
        .map_err(|_| "Não foi possível preparar os dados confidenciais".to_owned())?;
    let signed = SignedPrivateTransaction {
        transaction: proof_inputs,
        settlement_transfers,
    };
    let unsigned = Submission::new(&signed, payer, &wallet.nullifier)
        .finish_unsigned_sync(&client)
        .map_err(|_| "O provador TEE ou o indexador Helius não concluiu a prova; nada foi assinado".to_owned())?;

    save_prepared(
        wallet, operation, amount, review_counterparty,
        unsigned.message, unsigned.last_valid_block_height,
    )
}

fn prepared_review(wallet: &WalletContext) -> Result<String, String> {
    let prepared = wallet.prepared.as_ref().ok_or_else(|| "Nenhuma operação preparada".to_owned())?;
    let visibility = match prepared.operation {
        Operation::DepositSol => "Público: endereço, ativo e valor visíveis",
        Operation::TransferSol => "Confidencial: ativo e valor ocultos; endereço público",
        Operation::WithdrawSol => "Retirada pública: endereço, ativo e valor visíveis",
    };
    serde_json::to_string(&json!({
        "operation": prepared.operation.as_str(),
        "amountLamports": prepared.amount,
        "counterparty": prepared.counterparty,
        "visibility": visibility,
        "lastValidBlockHeight": prepared.last_valid_block_height,
        "messageBytes": prepared.message.len(),
        "signable": true
    })).map_err(|_| "Não foi possível preparar a revisão".to_owned())
}

fn cancel_prepared(handle: i64) -> Result<(), String> {
    with_wallet(handle, |wallet| {
        wallet.prepared = None;
        Ok(())
    })
}

fn verify_and_consume(
    wallet: &mut WalletContext,
    message: &[u8],
    approved_hash: &[u8],
    operation: &str,
) -> bool {
    let Some(prepared) = wallet.prepared.as_ref() else { return false };
    let digest = Sha256::digest(message);
    let expected = match prepared.operation {
        Operation::DepositSol => "deposit_sol",
        Operation::TransferSol => "transfer_sol",
        Operation::WithdrawSol => "withdraw_sol",
    };
    let valid = expected == operation
        && prepared.message.as_slice() == message
        && digest.as_slice() == approved_hash;
    if valid {
        wallet.prepared = None;
    }
    valid
}

fn new_wallet(public_key: [u8; 32], seed: Vec<u8>) -> Result<WalletContext, String> {
    let seed = Zeroizing::new(seed);
    if seed.len() != 64 {
        return Err("Material de derivação inválido".to_owned());
    }
    let challenge = ed25519_derivation_message(&public_key);
    let verifying_key = VerifyingKey::from_bytes(&public_key)
        .map_err(|_| "Chave pública inválida".to_owned())?;
    let signature = Signature::from_slice(seed.as_slice())
        .map_err(|_| "Material de derivação inválido".to_owned())?;
    verifying_key.verify_strict(&challenge, &signature)
        .map_err(|_| "A assinatura de derivação não pertence a esta carteira".to_owned())?;

    let (nullifier, viewing) = expand_roles(seed.as_slice(), Curve::Ed25519)
        .map_err(|_| "Não foi possível derivar as chaves do Default Ring".to_owned())?;
    let address = ShieldedAddress {
        signing_pubkey: PublicKey::from_ed25519(&public_key),
        nullifier_pubkey: nullifier.pubkey()
            .map_err(|_| "Não foi possível derivar a chave nullifier".to_owned())?,
        viewing_pubkey: viewing.pubkey(),
    };
    let keys = LocalShieldedKeys::new(address, vec![viewing], nullifier.clone())
        .map_err(|_| "Não foi possível inicializar a custódia confidencial".to_owned())?;
    Ok(WalletContext {
        public_key,
        shielded_address: address,
        nullifier,
        keys,
        prepared: None,
    })
}

fn with_wallet<T: SessionOutput>(
    handle: i64,
    f: impl FnOnce(&mut WalletContext) -> Result<T, String>,
) -> Result<T, String> {
    if handle <= 0 { return Err("Sessão de privacidade inválida".to_owned()); }
    // Clone the per-session Arc while holding the short-lived global-map lock,
    // then release it before any RPC/indexer/prover work.
    let session = lock_wallets()?.get(&handle).cloned()
        .ok_or_else(|| "Sessão de privacidade encerrada".to_owned())?;
    if session.closed.load(Ordering::Acquire) {
        return Err("Sessão de privacidade encerrada".to_owned());
    }
    let mut context = session.context.lock()
        .map_err(|_| "privacy session unavailable".to_owned())?;
    if session.closed.load(Ordering::Acquire) {
        return Err("Sessão de privacidade encerrada".to_owned());
    }
    let mut result = f(&mut context);
    if session.closed.load(Ordering::Acquire) {
        if let Ok(value) = &mut result { value.discard(); }
        return Err("Sessão de privacidade encerrada durante a operação".to_owned());
    }
    result
}

fn bytes(env: &mut JNIEnv, value: JByteArray) -> Result<Vec<u8>, String> {
    env.convert_byte_array(value).map_err(|_| "Dados binários inválidos".to_owned())
}

fn string(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value).map(|s| s.into()).map_err(|_| "Texto inválido".to_owned())
}

fn throw(env: &mut JNIEnv, message: &str) {
    let safe = message.chars().take(180).collect::<String>();
    let _ = env.throw_new("java/lang/IllegalStateException", safe);
}

fn result_byte_array(env: &mut JNIEnv, result: Result<Vec<u8>, String>) -> jbyteArray {
    match result {
        Ok(mut value) => {
            let output = env.byte_array_from_slice(&value)
                .map(|a| a.into_raw())
                .unwrap_or_else(|_| std::ptr::null_mut());
            value.zeroize();
            output
        }
        Err(message) => { throw(env, &message); std::ptr::null_mut() }
    }
}

fn result_string(env: &mut JNIEnv, result: Result<String, String>) -> jstring {
    match result {
        Ok(mut value) => {
            let output = env.new_string(value.as_str())
                .map(|s| s.into_raw())
                .unwrap_or_else(|_| std::ptr::null_mut());
            value.zeroize();
            output
        }
        Err(message) => { throw(env, &message); std::ptr::null_mut() }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_lockwallet_custody_local_privacy_PrivacyRingNative_nativeDerivationMessage(
    mut env: JNIEnv, _class: JClass, public_key: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let key: [u8; 32] = bytes(&mut env, public_key)?.try_into().map_err(|_| "Chave pública inválida".to_owned())?;
        Ok(ed25519_derivation_message(&key))
    })).unwrap_or_else(|_| Err("Falha interna na derivação".to_owned()));
    result_byte_array(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_lockwallet_custody_local_privacy_PrivacyRingNative_nativeOpenWallet(
    mut env: JNIEnv, _class: JClass, public_key: JByteArray, derivation_seed: JByteArray,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let key: [u8; 32] = bytes(&mut env, public_key)?.try_into().map_err(|_| "Chave pública inválida".to_owned())?;
        let seed = bytes(&mut env, derivation_seed)?;
        let wallet = new_wallet(key, seed)?;
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed) as i64;
        if handle <= 0 { return Err("Não foi possível abrir a sessão confidencial".to_owned()); }
        lock_wallets()?.insert(handle, Arc::new(WalletSession::new(wallet)));
        Ok(handle)
    })).unwrap_or_else(|_| Err("Falha interna ao abrir a sessão confidencial".to_owned()));
    match result {
        Ok(handle) => handle as jlong,
        Err(message) => { throw(&mut env, &message); 0 }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_lockwallet_custody_local_privacy_PrivacyRingNative_nativeShieldedAddress(
    mut env: JNIEnv, _class: JClass, handle: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| with_wallet(handle as i64, |wallet| Ok(wallet.shielded_address.to_string()))))
        .unwrap_or_else(|_| Err("Falha ao obter o endereço confidencial".to_owned()));
    result_string(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_lockwallet_custody_local_privacy_PrivacyRingNative_nativeScanSol(
    mut env: JNIEnv, _class: JClass, handle: jlong, rpc_url: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let rpc_url = string(&mut env, rpc_url)?;
        with_wallet(handle as i64, |wallet| {
            let (amount, utxos) = scan_sol(wallet, &rpc_url)?;
            serde_json::to_string(&json!({
                "asset": "SOL",
                "amountLamports": amount,
                "utxoCount": utxos
            })).map_err(|_| "Não foi possível formatar o saldo confidencial".to_owned())
        })
    })).unwrap_or_else(|_| Err("Falha ao sincronizar o saldo confidencial".to_owned()));
    result_string(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_lockwallet_custody_local_privacy_PrivacyRingNative_nativePrepareDepositSol(
    mut env: JNIEnv, _class: JClass, handle: jlong, rpc_url: JString, amount: jlong,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let rpc_url = string(&mut env, rpc_url)?;
        let amount = u64::try_from(amount).map_err(|_| "Valor de depósito inválido".to_owned())?;
        with_wallet(handle as i64, |wallet| prepare_deposit_sol(wallet, &rpc_url, amount))
    })).unwrap_or_else(|_| Err("Falha ao preparar o depósito".to_owned()));
    result_byte_array(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_lockwallet_custody_local_privacy_PrivacyRingNative_nativePrepareTransferSol(
    mut env: JNIEnv, _class: JClass, handle: jlong, rpc_url: JString, recipient: JString, amount: jlong,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let rpc_url = string(&mut env, rpc_url)?;
        let recipient = string(&mut env, recipient)?;
        let amount = u64::try_from(amount).map_err(|_| "Valor de transferência inválido".to_owned())?;
        with_wallet(handle as i64, |wallet| prepare_private_sol(wallet, &rpc_url, amount, &recipient, Operation::TransferSol))
    })).unwrap_or_else(|_| Err("Falha ao preparar a transferência confidencial".to_owned()));
    result_byte_array(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_lockwallet_custody_local_privacy_PrivacyRingNative_nativePrepareWithdrawSol(
    mut env: JNIEnv, _class: JClass, handle: jlong, rpc_url: JString, recipient: JString, amount: jlong,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let rpc_url = string(&mut env, rpc_url)?;
        let recipient = string(&mut env, recipient)?;
        let amount = u64::try_from(amount).map_err(|_| "Valor de retirada inválido".to_owned())?;
        with_wallet(handle as i64, |wallet| prepare_private_sol(wallet, &rpc_url, amount, &recipient, Operation::WithdrawSol))
    })).unwrap_or_else(|_| Err("Falha ao preparar a retirada".to_owned()));
    result_byte_array(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_lockwallet_custody_local_privacy_PrivacyRingNative_nativePreparedReview(
    mut env: JNIEnv, _class: JClass, handle: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| with_wallet(handle as i64, prepared_review)))
        .unwrap_or_else(|_| Err("Falha ao revisar a operação confidencial".to_owned()));
    result_string(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_lockwallet_custody_local_privacy_PrivacyRingNative_nativeVerifyAndConsume(
    mut env: JNIEnv, _class: JClass, handle: jlong, message: JByteArray, hash: JByteArray, operation: JString,
) -> jboolean {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let message = bytes(&mut env, message)?;
        let hash = bytes(&mut env, hash)?;
        let operation = string(&mut env, operation)?;
        with_wallet(handle as i64, |wallet| Ok(verify_and_consume(wallet, &message, &hash, &operation)))
    })).unwrap_or_else(|_| Err("Falha ao validar a revisão da operação".to_owned()));
    match result {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(message) => { throw(&mut env, &message); 0 }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_lockwallet_custody_local_privacy_PrivacyRingNative_nativeCancelPrepared(
    mut env: JNIEnv, _class: JClass, handle: jlong,
) {
    let result = catch_unwind(AssertUnwindSafe(|| cancel_prepared(handle as i64)))
        .unwrap_or_else(|_| Err("Falha ao cancelar a operação confidencial".to_owned()));
    if let Err(message) = result {
        throw(&mut env, &message);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_lockwallet_custody_local_privacy_PrivacyRingNative_nativeCloseWallet(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if let Ok(mut state) = wallets().lock() {
        if let Some(session) = state.remove(&(handle as i64)) {
            // Do not wait for an in-flight network operation. It will see this
            // flag when it returns, discard its output and drop the key context.
            session.closed.store(true, Ordering::Release);
        }
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey as TestSigningKey};

    fn fixture_wallet() -> (WalletContext, [u8; 32]) {
        let signing = TestSigningKey::from_bytes(&[7u8; 32]);
        let public_key = signing.verifying_key().to_bytes();
        let derivation_message = ed25519_derivation_message(&public_key);
        let signature = signing.sign(&derivation_message).to_bytes().to_vec();
        (new_wallet(public_key, signature).expect("valid TSPP derivation"), public_key)
    }

    #[test]
    fn derives_a_shielded_identity_from_the_wallet_domain_signature() {
        let (wallet, public_key) = fixture_wallet();
        assert_eq!(
            wallet.shielded_address.solana_address().expect("owner address").to_bytes(),
            public_key
        );
        assert_ne!(wallet.shielded_address.viewing_pubkey.as_bytes(), &[0u8; 33]);
        assert_ne!(wallet.shielded_address.nullifier_pubkey, [0u8; 32]);
    }

    #[test]
    fn refuses_a_derivation_signature_from_another_wallet() {
        let signing = TestSigningKey::from_bytes(&[9u8; 32]);
        let public_key = signing.verifying_key().to_bytes();
        let other = TestSigningKey::from_bytes(&[10u8; 32]);
        let message = ed25519_derivation_message(&public_key);
        let signature = other.sign(&message).to_bytes().to_vec();
        assert!(new_wallet(public_key, signature).is_err());
    }

    #[test]
    fn approval_is_bound_to_exact_bytes_and_operation_and_is_single_use() {
        let (mut wallet, _) = fixture_wallet();
        let message = vec![0x81, 1, 2, 3, 4];
        let digest = Sha256::digest(&message);
        wallet.prepared = Some(Prepared {
            operation: Operation::TransferSol,
            amount: 250_000_000,
            counterparty: "shielded-destination".to_owned(),
            message: message.clone(),
            last_valid_block_height: 99,
        });

        assert!(!verify_and_consume(&mut wallet, &message, &digest, "withdraw_sol"));
        assert!(wallet.prepared.is_some());
        let changed = [0x81, 9, 2, 3, 4];
        let changed_digest = Sha256::digest(changed);
        assert!(!verify_and_consume(&mut wallet, &changed, &changed_digest, "transfer_sol"));
        assert!(wallet.prepared.is_some());
        assert!(verify_and_consume(&mut wallet, &message, &digest, "transfer_sol"));
        assert!(wallet.prepared.is_none());
        assert!(!verify_and_consume(&mut wallet, &message, &digest, "transfer_sol"));
    }

    #[test]
    fn cancel_prepared_discards_the_operation() {
        let (mut wallet, _) = fixture_wallet();
        wallet.prepared = Some(Prepared {
            operation: Operation::DepositSol,
            amount: 500_000_000,
            counterparty: "wallet".to_owned(),
            message: vec![0x81, 1],
            last_valid_block_height: 100,
        });
        let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed) as i64;
        lock_wallets().expect("wallet map").insert(handle, Arc::new(WalletSession::new(wallet)));
        cancel_prepared(handle).expect("cancel");
        with_wallet(handle, |wallet| {
            assert!(wallet.prepared.is_none());
            Ok(())
        }).expect("session remains active");
        lock_wallets().expect("wallet map").remove(&handle);
    }
}
