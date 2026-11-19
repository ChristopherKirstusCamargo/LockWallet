# ADR 0002 — PIN, codificação, Argon2id, autenticação por uso e limites sem biometria

- **Status:** Aceito — implementado na Fase B (`Pin`, `EnvelopeCrypto`, `AndroidKeystoreKekStore`)
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Spec §6.2–6.3, v2.1 §1–2, v2.2 SEC-02, prompt 1.2 (PIN de 6 dígitos + BIOMETRIC_STRONG preferido; 5 erros ⇒ bloqueio de 1 h).

## Decisão
- **Codificação do PIN:** somente dígitos ASCII `0`–`9` (0x30–0x39); outros dígitos Unicode (ex.: `٣`, `３`) são rejeitados na entrada, nunca convertidos. Bytes = ASCII. Comprimento 6–12; padrão 6 (conveniência), recomendado ≥ 8. Mantido em CharArray/ByteArray e limpo em `finally`.
- **KDF:** Argon2id v=0x13, saída 32 B, salt 16 B aleatório, perfil `0x01` = m 19456 KiB, t 2, p 1 (piso OWASP). Perfis mais fortes só com benchmark em aparelho de entrada + novo id.
- **Perfil reforçado:** KEK com `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` (auth-per-use) liberada só via `BiometricPrompt.CryptoObject`; nunca booleano. **Perfil base:** KEK sem autenticação por uso ⇒ risco declarado: quem obtiver `dek_pin` pode testar PINs offline.
- **Erro genérico:** PIN errado, tag inválida e blob adulterado produzem o mesmo resultado para a UI.

**Implementação:** a UI aceita exatamente 6 dígitos e recusa sequências/repetidos; KEK com `setUnlockedDeviceRequired` quando há bloqueio de tela, `setInvalidatedByBiometricEnrollment(true)` no perfil reforçado, StrongBox pedido só se `FEATURE_STRONGBOX_KEYSTORE` e nível real lido do `KeyInfo`. Ordem no desbloqueio: PIN digitado → BiometricPrompt(CryptoObject) → contador write-ahead → unwrap KEK → Argon2id → DEK → material → pubkey. Duas solicitações biométricas ao adicionar carteira (verificar PIN + selar), pois auth-per-use autoriza uma única operação.

## Alternativas rejeitadas
Senha alfanumérica obrigatória para todos (prejudica UX; oferecida como opção “segredo de alta entropia”); normalização de dígitos Unicode (ambígua); Argon2id mais fraco para UX.

## Consequências
Central de Segurança diferencia “PIN de conveniência”, “segredo de alta entropia” e “autenticação por uso vinculada ao hardware”.

## Risco residual
6 dígitos = ~20 bits: sem auth-per-use, ataque offline é viável se a KEK for abusada no aparelho.

## Evidência
OWASP Password Storage Cheat Sheet (G3-1); RFC 9106; Android Keystore auth docs (G2-1).

## Teste
Fase B: rejeição de dígitos não ASCII, vetor Argon2id RFC 9106 + oracle independente, PIN errado ≡ blob adulterado; aparelho físico: KEK inutilizável fora do BiometricPrompt.

## Condição para reabrir
Benchmark mostrar DoS/UX inaceitável ou nova API de hardware (ex.: Weaver público).
