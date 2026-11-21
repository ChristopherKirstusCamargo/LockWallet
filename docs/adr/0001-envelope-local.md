# ADR 0001 — Envelope local, hierarquia de chaves e contrato binário (LWLE v1)

- **Status:** Aceito — implementado na Fase B (com a correção E-23)
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
A v2.0 §6.1 descreve as camadas AES-GCM; v2.1 §4 e v2.2 SEC-01 exigem um contrato binário fechado antes de qualquer código de custódia. StrongBox/TEE só suportam AES/RSA/EC P-256/HMAC (não Ed25519).

## Decisão
**Hierarquia (ordem de unwrap):** `dek_pin_blob` --Keystore AES-256-GCM (KEK, alias local)--> `dek_pin` --AES-256-GCM(pin_key = Argon2id(PIN))--> `DEK` --AES-256-GCM--> `material`. Em seguida deriva-se a pubkey e compara-se com `expected_pubkey`; só então o cofre é considerado aberto. A chave Ed25519 é derivada e usada em software dentro de `:custody:local`; o hardware protege apenas a KEK.

**Arquivo LWLE v1** (todos os inteiros Big-Endian, sem campos opcionais):

| Off | Tam | Campo | Regra |
|---|---|---|---|
| 0 | 4 | magic | `LWLE` (4C 57 4C 45) |
| 4 | 1 | envelope_version | 0x01 |
| 5 | 1 | suite | 0x01 = AES-256-GCM(128-bit tag) + Argon2id v0x13 out 32 + Android Keystore AES-256-GCM |
| 6 | 16 | vault_id | aleatório, estável por cofre |
| 22 | 16 | material_id | aleatório por material |
| 38 | 1 | material_kind | 0x01 BIP39_ENTROPY, 0x02 IMPORTED_ED25519_KEYPAIR (mesmos ids do LWRP) |
| 39 | 1 | schema_version | 0x01 |
| 40 | 1 | derivation_profile | 0x01/0x02 (BIP-39) ou 0x00 |
| 41 | 4 | account_index | < 2^31; 0 para keypair |
| 45 | 32 | expected_pubkey | raw |
| 77 | 1 | kdf_profile_id | 0x01 legacy = m 19456 KiB, t 2, p 1; 0x02 current = m 65536 KiB, t 3, p 1 (allowlist) |
| 78 | 16 | pin_salt | aleatório |
| 94 | 16 | keystore_alias_id | aleatório; alias = `lw.kek.` + hex |
| 110 | 1 | auth_mode | 0x01 PIN-only, 0x02 PIN + BIOMETRIC_STRONG auth-per-use |
| 111 | 1 | flags | 0x00 (bits desconhecidos ⇒ rejeitar) |
| 112 | 2 | material_length | 16/20/24/28/32 ou 64 |
| 114 | 12 | seed_nonce | AES-GCM nonce do material |
| 126 | 4 | seed_ct_len | = material_length + 16 |
| 130 | n | seed_ct‖tag | |
| … | 12 | dekpin_nonce | |
| … | 12 | hw_iv | IV gerado pelo Keystore |
| … | 2 | hw_ct_len | = 48 + 16 |
| … | 64 | dek_pin_blob‖tag | |

Tamanho total exato = 130 + (material_length+16) + 12 + 12 + 2 + 64 = material_length + 236; qualquer outro tamanho é rejeitado antes de KDF.

**Correção E-23 (Fase B):** a tabela da Fase A gravava também `dek_pin‖tag` em claro ao lado do seu wrap pelo Keystore — isso permitiria testar PINs offline **sem** a KEK. `dek_pin` agora só existe dentro de `dek_pin_blob`. `pin_salt` é por cofre: novas carteiras do mesmo cofre reutilizam o salt da carteira que acabou de verificar o PIN (`PinVerification`, uso único), com DEK e nonces novos por arquivo.

**AAD por domínio** (`H` = bytes 0..113 do cabeçalho, que incluem vault_id, material_id, kind, perfil, índice, pubkey, KDF, alias, auth_mode):
`seed_ct`: `"lock-local-seed-v1" ‖ 0x00 ‖ H`; `dek_pin`: `"lock-local-pin-v1" ‖ 0x00 ‖ H`; `dek_pin_blob`: `"lock-local-hw-v1" ‖ 0x00 ‖ H`. Domínios distintos; nonces aleatórios de 96 bits, uma única cifragem por chave/nonce (DEK, pin_key e KEK nunca cifram mais de um objeto por versão do arquivo).

**Migração/escrita:** temp → fsync → read-back → validar estrutura, autenticação, material e pubkey derivada → rename atômico; versão anterior preservada até sucesso; versão futura desconhecida é recusada sem tocar no arquivo (downgrade nunca reescreve).

**Migração KDF (adendo 21/11/2026):** LWLE continua com o mesmo layout v1 e o mesmo AAD; `kdf_profile_id` diferencia os parâmetros. Novas gravações usam `0x02` (Argon2id m=65536 KiB, t=3, p=1). Ao abrir um envelope `0x01` com PIN válido, a aplicação prepara um novo envelope sob `0x02`, verifica novamente DEK/material/pubkey e só substitui o arquivo por rename atômico. Cancelamento ou falha deixa o arquivo anterior utilizável e a migração pode ser tentada de novo. Essa migração **não** muda `auth_mode` nem a KEK: envelopes legados `PIN_ONLY` continuam sem autenticação por uso e exigem restauração/recriação em cofre novo para obter esse controle.

## Alternativas rejeitadas
JSON/Protobuf como contrato criptográfico (ordem/representação variável); AAD apenas com string fixa (não vincula metadados); assinar Ed25519 no Keystore (não suportado); recriar alias após falha (proibido, §7.4).

## Consequências
Arquivos de carteiras diferentes não podem ser misturados (vault_id/material_id no AAD). Qualquer alteração de layout exige `envelope_version` novo e vetores de regressão.

## Risco residual
Processo comprometido no mesmo UID pode usar a KEK enquanto desbloqueada; JVM não garante apagamento (ADR 0013).

## Evidência
Android Keystore docs (G1-1, G1-2 no verification-log); v2.1 §4; v2.2 SEC-01.

## Teste
Implementado anteriormente: `LocalEnvelopeTest` (3 envelopes byte a byte × Python independente, negativos estruturais, bit-flip em todos os bytes e mistura de blobs), `LocalSecureProviderTest` e `AtomicFilesTest`. Adicionados testes para perfil `0x02` e migração v1→v2 com reabertura em instância nova; ainda precisam ser executados em CI/aparelho físico antes de considerar a migração validada.

## Condição para reabrir
Mudança de suite/algoritmo, evidência de falha de Keystore em OEM, ou necessidade de multi-material por arquivo.
