# ADR 0002 — PIN, codificação, Argon2id, autenticação por uso e limites sem biometria

- **Status:** Aceito — implementado na Fase B (`Pin`, `EnvelopeCrypto`, `AndroidKeystoreKekStore`)
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Spec §6.2–6.3, v2.1 §1–2, v2.2 SEC-02, prompt 1.2 (PIN de 6 dígitos + BIOMETRIC_STRONG preferido; 5 erros ⇒ bloqueio de 1 h).

## Decisão
- **Codificação do PIN:** somente dígitos ASCII `0`–`9` (0x30–0x39); outros dígitos Unicode (ex.: `٣`, `３`) são rejeitados na entrada, nunca convertidos. Bytes = ASCII. Comprimento 6–12; padrão 6 (conveniência), recomendado ≥ 8. Mantido em CharArray/ByteArray e limpo em `finally`.
- **KDF:** Argon2id v=0x13, saída 32 B, salt 16 B aleatório. Perfil `0x01` (m=19456 KiB, t=2, p=1) permanece apenas para ler cofres legados. Novas gravações usam `0x02` (m=65536 KiB, t=3, p=1), sempre por allowlist fixa. O perfil novo aumenta o custo offline, mas não transforma um PIN de 6 dígitos em segredo de alta entropia; a KEK por uso continua sendo uma defesa distinta. O benchmark em aparelhos Android reais ainda é obrigatório.
- **Cofres novos:** obrigatoriamente KEK com `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` (auth-per-use), liberada apenas por `BiometricPrompt.CryptoObject`. A criação também exige bloqueio de tela seguro e biometria forte cadastrada. Não existe opção nova `PIN_ONLY` na interface. **Compatibilidade:** cofres antigos `PIN_ONLY` continuam legíveis, mas a Central de Segurança os marca como risco; a migração KDF não altera a KEK nem o modo de autenticação.
- **Erro genérico:** PIN errado, tag inválida e blob adulterado produzem o mesmo resultado para a UI.

**Implementação:** a UI aceita exatamente 6 dígitos e recusa sequências/repetidos; KEK com `setUnlockedDeviceRequired` quando há bloqueio de tela e `setInvalidatedByBiometricEnrollment(true)`; StrongBox é solicitado somente se `FEATURE_STRONGBOX_KEYSTORE` e o nível real é lido do `KeyInfo`. Ordem no desbloqueio: PIN digitado → BiometricPrompt(CryptoObject) → contador write-ahead → unwrap KEK → Argon2id do perfil gravado → DEK → material → pubkey. Duas confirmações biométricas ao adicionar carteira (verificar PIN + selar), pois auth-per-use autoriza uma única operação. Para envelopes legados `0x01`, a primeira abertura correta prepara `0x02` e só o substitui após validação e rename atômico.

## Alternativas rejeitadas
Senha alfanumérica obrigatória para todos (prejudica UX; oferecida como opção “segredo de alta entropia”); normalização de dígitos Unicode (ambígua); Argon2id mais fraco para UX.

## Consequências
Central de Segurança diferencia “PIN de conveniência”, “segredo de alta entropia” e “autenticação por uso vinculada ao hardware”.

## Risco residual
6 dígitos = ~20 bits: o perfil KDF `0x02` encarece tentativas offline, mas não substitui entropia maior nem autenticação por uso. Cofres legados `PIN_ONLY` continuam mais expostos até serem restaurados em um novo cofre. O perfil de 64 MiB/3 passagens precisa de benchmark no A54 e em aparelhos Android de menor capacidade; ainda não há medição física.

## Evidência
OWASP Password Storage Cheat Sheet (G3-1); RFC 9106; Android Keystore auth docs (G2-1).

## Teste
Fase B: rejeição de dígitos não ASCII, vetor Argon2id RFC 9106 + oracle independente, PIN errado ≡ blob adulterado; aparelho físico: KEK inutilizável fora do BiometricPrompt.

## Condição para reabrir
Benchmark mostrar DoS/UX inaceitável ou nova API de hardware (ex.: Weaver público).
