# ADR 0022 — Lock Connect: Mobile Wallet Adapter (Fase H)

- **Status:** Aceito
- **Data:** 2026-11-19
- **Spec:** v2.0 §2.3, §23.4; ADR 0016/0019; errata E-32..E-35.

## Decisão
- **Biblioteca:** `com.solanamobile:mobile-wallet-adapter-walletlib:2.1.1` (lado carteira, MWA 2.x). A 2.2.x exige kotlin-stdlib 2.4 e BC 1.86 (incompatível com o compilador Kotlin 2.2.21 do projeto). Dependências transitivas: `mobile-wallet-adapter-common`, `Java-WebSocket 1.6.0`, `slf4j-android 1.7.36` — todas com sha256 em `verification-metadata.xml`. Logs da walletlib/slf4j usam `android.util.Log` e são removidos no release pelo R8 (`assumenosideeffects`).
- **Entrada:** `LockConnectActivity` (subclasse da `MainActivity`, mesma janela protegida: FLAG_SECURE, filterTouchesWhenObscured, HIDE_OVERLAY_WINDOWS, bloqueio ao sair). É o **segundo componente exportado** (antes só o launcher) com um único filtro `VIEW` + `solana-wallet:`. Aceita **somente associação local** (dApp no próprio aparelho, WebSocket em localhost); associação remota (reflector) é recusada — o spec trata o reflector como adversário e exige escolha explícita; não oferecida na v1. `ACCESS_NETWORK_STATE` trazida pela walletlib é removida.
- **Sessões:** registro próprio (`noBackupFilesDir/connect/sessions.json`): o *scope* do token MWA é o id da sessão. Autorização curta (token 1 h; reautorização até 24 h) e **toda** reautorização/assinatura é conferida no nosso registro → revogar na aba dApps vale na hora. `deauthorize` do dApp revoga. Sem `clone_authorization` (descontinuado).
- **Carteiras:** só papel **Lock Connect** (frase separada). Lock Vault nunca é oferecida.
- **Rede:** só Devnet/Testnet **e** igual à rede selecionada. `solana:mainnet`/`mainnet-beta`, rede ausente ou desconhecida ⇒ `ERROR_CLUSTER_NOT_SUPPORTED` sem mostrar nada. Rede diferente da selecionada ⇒ explicação e recusa.
- **Identidade:** nome/URI informados pelo dApp, exibidos como **não verificados**; pacote Android chamador (quando o Android informa) exibido; ícone remoto nunca carregado.
- **Firewall de programas (`DappFirewall`, core:solana, puro):** só mensagens legacy; exatamente 1 assinante = a carteira = fee payer; instruções de programas centrais (System, Token, Token-2022, ATA, Compute Budget, Stake) são decodificadas com as **mesmas regras do analisador** — qualquer instrução central fora da allowlist (Approve, SetAuthority, Assign, nonce, Authorize de stake…) **bloqueia**; loaders/upgrade, Vote, Config, Feature e Address Lookup Table **bloqueiam**; Memo é mostrado como texto; programas desconhecidos (e pré-compilados nomeados) aparecem **crus** (ID do programa, contas com permissões assina/gravável, “SUA CARTEIRA”, tamanho e prefixo dos dados) e exigem **reconhecimento explícito com aviso forte**.
- **Simulação obrigatória** com estado posterior da carteira e das contas de token da carteira que a transação grava: variação de SOL e de tokens exibida; bloqueia se a carteira mudar de dono/programa, se uma conta de token mudar de dono, ganhar delegado ou mudar a autoridade de fechamento. Blockhash do dApp conferido com `isBlockhashValid`; nada é reconstruído.
- **Custódia re-verifica:** `UnlockedWallet.signDappTransaction` / `SeedVaultProvider.signDappTransactionRequest` re-executam o firewall, conferem o hash revisado, o papel CONNECT e o reconhecimento cru antes de assinar. PIN (ou Seed Vault) **a cada pedido**.
- **sign_and_send:** 1 transação por pedido (o coordenador nunca tem 2 envios sem resultado por carteira); persistida (SIGNED) antes do envio, mesmos bytes, reconciliação normal. **sign_transactions:** até 4, devolvidas assinadas, sem envio pela carteira.
- **Mensagens / SIWS:** só texto UTF-8 apresentável (sem controle, bidi ou invisíveis, ≤ 2048 B) e nunca algo que seja interpretável como transação/mensagem Solana; resultado = mensagem‖assinatura (convenção MWA). SIWS: texto preparado pela walletlib, aviso forte se o domínio não confere com a URI do dApp ou se a rede citada diverge.

## Riscos residuais
- Sem verificação de origem (Digital Asset Links); identidade é autodeclarada. Programas desconhecidos dependem do julgamento do usuário + simulação (o RPC pode mentir na simulação — T-34).
- Co-assinatura (multi-signer) e transações v0 de dApps não são suportadas.
- Não testado em aparelho com dApp real neste ambiente (testes Robolectric com respostas falsas da walletlib).

## Teste
`DappFirewallTest` (5), `ConnectFlowTest` (4), `SolanaUriTest`, `ManifestAndWindowSecurityTest`, `verify_apk_manifest.py`.
