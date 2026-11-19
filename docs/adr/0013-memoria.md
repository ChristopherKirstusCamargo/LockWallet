# ADR 0013 — Política de memória e zeroization best-effort

- **Status:** Aceito — aplicado na Fase A
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Spec §8.

## Decisão
`SecretBytes` (dono do buffer, `toString` redigido, `close()` zera), APIs de segredo sem `String` (teste por reflexão), exceções sem bytes/palavras, buffers intermediários do PBKDF2/HMAC/SLIP-0010 limpos em `finally`. Linguagem: “reduz o tempo de permanência e tenta limpar buffers”.

**Fase B/C:** material só em `:custody:local` (`PendingMaterial`/`UnlockedWallet`, `close()` zera); UI recebe palavras como `CharArray` e as desenha no Canvas (sem `String`), PIN em `CharArray` próprio do teclado, tudo limpo em `onDispose`/`onStop`. Limitações aceitas: `TextField` do Compose (frase/chave/senha digitadas) e `java.text.Normalizer` (senha LWRP não-NFC) criam `String`.

## Alternativas rejeitadas
Código nativo para zeroization.

## Consequências
Pequeno custo de cópias controladas.

## Risco residual
BC copia chaves internamente (`KeyParameter`), ART/GC podem mover dados.

## Evidência
Spec §8.

## Teste
`SecretHygieneTest`, `Bip39ValidationTest.exceptionMessagesNeverContainWords`.

## Condição para reabrir
Biblioteca com buffers controláveis.
