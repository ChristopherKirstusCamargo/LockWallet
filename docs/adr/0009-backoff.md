# ADR 0009 — Backoff local, rollback e limites anti-rollback

- **Status:** Aceito — implementado na Fase B (`PinAttemptLimiter`)
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Spec §11; prompt 1.2: 5 PINs errados ⇒ bloqueio de 1 h, persistente, nunca apaga a carteira.

## Decisão
Contador write-ahead (persistido de forma síncrona antes do Argon2id) em armazenamento transacional; relógio `elapsedRealtime` + boot count; após 5 erros: 1 h (prompt 1.2), depois dobrando até 24 h. Falha de escrita ⇒ falha fechada para autenticação, dados preservados. Nunca apaga a carteira. Recuperação por frase/LWRP sempre disponível.

**Implementação:** 1–4 ⇒ 0; 5 ⇒ 1 h; 6 ⇒ 2 h; 7 ⇒ 4 h; 8 ⇒ 8 h; 9 ⇒ 16 h; ≥ 10 ⇒ 24 h. Duas cópias com checksum (vale a mais restritiva); ambas ausentes/corrompidas com cofre existente ⇒ “uma tentativa antes do bloqueio”; reboot (BOOT_COUNT diferente) reinicia o bloqueio pela duração inteira. Falha de escrita ⇒ autenticação recusada.

## Alternativas rejeitadas
Apagar carteira após N erros (perda de fundos); confiar em relógio de parede.

## Consequências
Usuário bloqueado espera; pode restaurar em outro aparelho.

## Risco residual
Root/snapshot pode restaurar contador antigo — backoff é defesa de UX, não fronteira criptográfica.

## Evidência
Spec §11.2.

## Teste
Fase B: clock fake, crash entre incremento e KDF, arquivo removido/antigo.

## Condição para reabrir
API pública de contador monotônico em hardware.
