# ADR 0011 — Segurança base e dinâmica por capacidades

- **Status:** Aceito
- **Data:** 2026-11-19
- **Spec:** LOCK WALLET v2.0 + v2.1 + v2.2 (+ prompt 1.2 do proprietário); ver `docs/errata.md`.

## Contexto
Spec §4; prompt 1.2 `DeviceSecurityCapabilityManager`.

## Decisão
`DeviceSecurityCapabilityManager` (`:custody:local`, Fase B/C) produz fatos observados: securityLevel real da KEK via `KeyInfo` (STRONGBOX/TRUSTED_ENVIRONMENT/SOFTWARE), `FEATURE_STRONGBOX_KEYSTORE`, BIOMETRIC_STRONG disponível, bloqueio de tela seguro, backup desligado, rede, root/bootloader quando detectável. Estados textuais (Disponível/Indisponível/Ativado/…); sem nota numérica. Já na Fase A: `FLAG_SECURE` e `filterTouchesWhenObscured` em toda a janela; `HIDE_OVERLAY_WINDOWS` + `setHideOverlayWindows(true)` entram com as telas de frase/assinatura (Fase C).

## Alternativas rejeitadas
Nota 1–10; bloquear saque por root.

## Consequências
Mensagens honestas por aparelho.

## Risco residual
Detecção de root é heurística.

## Evidência
Android Keystore (G1-1).

## Teste
Robolectric `ManifestAndWindowSecurityTest`; aparelho físico para KeyInfo.

## Condição para reabrir
Nova API de atestado/integridade.
