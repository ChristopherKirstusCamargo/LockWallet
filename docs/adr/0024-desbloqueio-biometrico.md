# ADR 0024 — Desbloqueio por biometria (alternativa ao PIN)

- Estado: aceito (3.0.0)
- Contexto: requisito de produto — desbloquear com a digital em vez de digitar o PIN, sem abrir mão da segurança.

## Decisão

1. **Chave dedicada no Keystore**: par RSA (3072 bits; 2048 em StrongBox), `PURPOSE_DECRYPT`, OAEP-SHA-256,
   `setUserAuthenticationRequired(true)` + `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` (autenticação
   **por uso**, só biometria forte, sem credencial do aparelho), `setInvalidatedByBiometricEnrollment(true)`,
   `setUnlockedDeviceRequired(true)` quando há bloqueio de tela. Alias novo e aleatório a cada ativação; nunca
   sobrescrito nem recriado em silêncio.
2. **Registro por carteira** (`bio/<id>.lwbio`): chave AES-256 aleatória cifrada com a chave **pública** (sem prompt,
   por isso pode ser escrito logo após um desbloqueio por PIN) e o material cifrado com AES-GCM; AAD liga o registro à
   carteira, ao alias atual e ao hash do cabeçalho do envelope LWLE (trocar PIN ⇒ registro velho é descartado).
   Parser estrito de tamanho exato; qualquer erro ⇒ registro ignorado e apagado, PIN continua funcionando.
3. **Um único prompt**: `BiometricPrompt` com `CryptoObject(Cipher RSA)`; a chave privada só decifra a chave AES depois
   da biometria. O material aberto é conferido re-derivando a chave pública (igual ao caminho do PIN).
4. **Política** (estado `bio/state.lwbs`, autenticado com HMAC-SHA256 de uma chave Keystore companheira — editar o
   arquivo desliga o recurso, nunca o estende): biometria só substitui o PIN se
   - mesmo boot que o último PIN correto (reiniciar ⇒ PIN), relógio monotônico não voltou;
   - no máximo **72 h** desde o último PIN (mesma regra do Android para “autenticação forte”);
   - nenhum erro de PIN pendente e nenhum bloqueio por tentativas.
5. **Escopo**: biometria serve para desbloquear o app e para assinar (envio, stake, dApp). **Ver frase, exportar
   LWRP, adicionar conta, criar/selar carteira e ativar a própria biometria exigem o PIN** — imposto na custódia
   (`UnlockedWallet.words/exportLwrp/toPending/pinVerification` recusam sessão biométrica), não só na UI.
6. **Falhas nunca apagam nada**: cancelar, biometria errada, chave invalidada ⇒ volta ao teclado do PIN; não conta
   como tentativa de PIN. Invalidação (nova digital cadastrada, bloqueio de tela removido) ⇒ recurso desligado com
   aviso; o PIN funciona.
7. **Padrão desligado**; atualização da 2.0.0 chega desligada (nenhum registro existe). Ligar exige o PIN agora.

## Troca (trade-off)

- O material passa a existir também cifrado sob uma segunda chave (biométrica). Um atacante precisa da biometria do
  usuário **ou** de comprometer o TEE/StrongBox — o mesmo nível que já protege o KEK. Em troca o PIN é digitado menos
  vezes, o que reduz exposição a olhares e gravação de tela.
- Biometria é “algo que você é”: pode ser forçada (coação, usuário dormindo). Mitigações: janela padrão de 24 h (configurável, ver adendo), “Bloquear agora”, PIN após
  reiniciar, PIN para frase/exportação, desligar a qualquer momento nos Ajustes. Quem teme coação deve deixar
  desligado (texto na Ajuda).
- Alternativa rejeitada: envolver a chave do PIN com uma chave AES biométrica — no modo PIN+biometria exigiria dois
  prompts; e chave com `AUTH_DEVICE_CREDENTIAL` permitiria o PIN do aparelho no lugar do da carteira.

## Adendo (versão final) — prazo configurável e “Bloquear agora”

- Prazo máximo sem PIN agora configurável: **1, 4, 24 (padrão) ou 72 h**. Encurtar é imediato; **aumentar exige o
  PIN** (imposto na custódia: `setWindow(viaPin=false)` mais longo ⇒ `SecurityException`).
- **“Bloquear agora”** (Ajustes) grava `lockdown=true` no estado da política e bloqueia o app; a biometria fica
  recusada (`PIN_LOCKDOWN`) até o próximo PIN correto.
- Estado v2 (36 bytes de corpo: + prazo + flag), selado pelo mesmo HMAC Keystore; editado ⇒ biometria desligada;
  estado v1 (sem prazo) é lido com o padrão de 24 h.
- Contador de boot indisponível (−1) ⇒ `PIN_AFTER_RESTART` sempre (antes, só a checagem de uptime).
