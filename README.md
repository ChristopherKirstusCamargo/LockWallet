# Lock Wallet

**Carteira Solana de autocustódia para Android — segurança em primeiro lugar.**
Só Solana. Só stake nativo. Nada é assinado às cegas. Sem servidor próprio, sem telemetria, sem cadastro.

[Português](#português) · [English](#english)

| Verificação do release | Valor |
|---|---|
| Pacote | `com.lockwallet.app` |
| SHA-256 do APK (`lockwallet.apk`) | `1833c24e0ffa74bb2b07615493b03f9c7c294ba3acd83ca6fb039e230f43be09` |
| SHA-256 do certificado de assinatura | `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b` |
| Android mínimo | 12 (API 31) |
| Rede | Solana Mainnet (somente) |
| Licença | Apache-2.0 |

---

## Português

### Por que a Lock Wallet existe

A Lock Wallet nasceu de uma decisão do Christopher: ter a **própria carteira Solana de autocustódia**,
séria e construída do zero com a segurança acima de tudo — e levada mais a sério do que o seu projeto anterior, o
rastreador de portfólio CoinPortracker. Os princípios foram fixados antes da primeira linha de código:

- **Só Solana**, sem pontes nem outras redes.
- **Sem assinatura às cegas**: toda transação é decodificada, analisada e simulada antes de você aprovar.
- **Só stake nativo** — nunca stake líquido, pools ou tokens de recibo.
- **Sem telemetria, sem logs, sem servidor próprio.**
- **Design preto e branco em vidro**, limpo e direto, com explicações só quando você pede (ícone “?”).

O projeto foi conduzido por uma especificação mestre (escopo, ameaças, critérios de aceite e portões de fase) e
registrado em decisões de arquitetura (`docs/adr/`), modelo de ameaças (`docs/threat-model.md`) e relatório de
segurança (`docs/RELATORIO-SEGURANCA.md`).

### Funcionalidades

- **Criar ou restaurar** carteira por frase de 12/24 palavras (BIP-39, derivação SLIP-0010 `m/44'/501'/n'/0'`),
  importar chave privada, várias contas e várias carteiras.
- **Custódia local** cifrada (Argon2id + AES-GCM) com chave do Android Keystore; ou **Seed Vault** em aparelhos
  Solana Mobile — a semente nunca entra no app.
- **Receber** com QR e endereço completo em blocos.
- **Enviar SOL e tokens** SPL / Token-2022 (extensões perigosas negadas por padrão), com revisão do destino, taxa
  exata, simulação e confirmação por PIN.
- **Saldo total** = SOL disponível + todo o seu SOL em stake, com detalhe *Disponível / Em stake* e a recompensa da
  última época quando a rede a informa de forma consistente; valor em 10 moedas (USD, EUR, JPY, GBP, CNY, BRL, CAD,
  AUD, CHF, INR).
- **Stake nativo** com bloco de 6 validadores recomendados conferidos ao vivo on-chain, ordenados por **segurança
  primeiro e retorno estimado depois** (comissão efetiva e desempenho medidos na rede). Modo Avançado com lista
  completa e avisos. Desativar, sacar, dividir e juntar contas de stake.
- **dApps (Lock Connect)**: Mobile Wallet Adapter 2.x, associação local, sessões por dApp com revogação, cada pedido
  passa pelo analisador.
- **Backup**: frase de recuperação e Recovery Package (`.lwrp`) cifrado com senha de 8 palavras gerada.
- **Desbloqueio com digital** opcional, com PIN obrigatório após reiniciar, após erro de PIN e a cada 1/4/24/72 h
  (padrão 24 h), e botão **“Bloquear agora”**.
- **Central de Segurança** com o que o aparelho realmente oferece (StrongBox/TEE, biometria, sinais de root/depurador).
- Tema escuro e claro, sem anúncios, sem conta, sem e-mail.

### Instalação pelo APK (passo a passo)

1. **Baixe** `lockwallet.apk` do link de download do release. O hash esperado também está em
   `release/lockwallet.apk.sha256`.
2. **Confira o SHA-256 do arquivo** antes de instalar:
   - Linux/macOS: `sha256sum lockwallet.apk` (macOS: `shasum -a 256 lockwallet.apk`)
   - Windows (PowerShell): `Get-FileHash lockwallet.apk -Algorithm SHA256`
   - O resultado tem que ser exatamente: `1833c24e0ffa74bb2b07615493b03f9c7c294ba3acd83ca6fb039e230f43be09`
3. **Confira o certificado de assinatura** (Android SDK build-tools):
   ```
   apksigner verify --print-certs lockwallet.apk
   ```
   A linha `Signer #1 certificate SHA-256 digest` tem que ser
   `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b`. Esse certificado é o mesmo em todas as versões:
   o Android só aceita atualizações assinadas por ele.
4. **Instale**: abra o arquivo no celular e permita “instalar apps desta fonte” só para o app que você usou para abrir
   (desative depois). Ou pelo computador: `adb install lockwallet.apk`.
5. Abra a **Lock Wallet**.

### Como usar

**Criar ou restaurar**
1. Toque em **Criar uma carteira** (ou **Adicionar uma carteira existente** para restaurar).
2. Escolha onde a chave fica: **neste aparelho** (cofre da Lock Wallet) ou **Seed Vault**, quando o celular tem.
3. Ao criar: anote as 12 ou 24 palavras **no papel**, confirme no teste de palavras e defina um **PIN de 6 dígitos**.
4. Ao restaurar: digite a frase (o teclado é de senha e a área de transferência é apagada ao colar), escolha a conta e
   defina o PIN.

**Receber**
1. Na aba **Início**, toque em **Receber**.
2. Mostre o QR ou compartilhe o endereço. Confira com quem vai enviar o endereço inteiro, bloco por bloco.

**Enviar**
1. **Início → Enviar**, cole ou leia (QR) o endereço e digite o valor (vírgula para decimais, ex.: `1,5`).
2. Na **revisão**, confira destino completo, valor, taxa e avisos (endereço parecido com um seu, conta nova, etc.).
3. Confirme com o PIN (ou digital, se ativada). “Máximo” usa só o saldo **Disponível**, nunca o stake.

**Stake**
1. Aba **Stake → Novo stake**.
2. Escolha um validador do **bloco recomendado** (mais seguro e com maior retorno estimado no topo; o “?” explica a
   ordem) ou use **Avançado** se souber exatamente para quem delega.
3. Digite o valor, revise e confirme. O stake fica numa conta **sua**: você é sempre a autoridade.
4. Para tirar: **Desativar** e, após o fim da época, **Sacar**.

**dApps**
1. Num dApp Solana no mesmo celular, escolha conectar uma carteira (Mobile Wallet Adapter) e selecione a Lock Wallet.
2. Aprove a conexão; cada transação ou mensagem pedida aparece decodificada para revisão. Pedidos de redes de teste,
   taxas abusivas ou instruções fora da lista permitida são recusados.
3. Revogue sessões na aba **dApps**.

**Backup**
1. **Ajustes → Carteiras →** sua carteira.
2. **Ver frase de recuperação** (exige o PIN) — guarde no papel, nunca em foto.
3. **Exportar Recovery Package** (exige o PIN): um arquivo `.lwrp` cifrado com uma senha de 8 palavras gerada na hora.
   Guarde o arquivo e a senha em lugares diferentes.

**Segurança no dia a dia**
- **Ajustes → Desbloquear com a digital**: escolha o prazo máximo sem PIN (1, 4, 24 ou 72 h).
- **Ajustes → Bloquear agora**: bloqueia e exige o PIN no próximo desbloqueio.

### Modelo de segurança

- **Nada é assinado sem você ver e aprovar**, e o que é assinado é exatamente o que foi mostrado (o hash revisado é o
  hash assinado). Lista fechada de instruções; desconhecido ⇒ recusado ou mostrado cru com aviso forte.
- **Simulação obrigatória** antes de assinar; taxa de prioridade acima de 0,005 SOL bloqueada.
- **Chaves só no aparelho**: semente cifrada com Argon2id + AES-GCM e chave do Keystore (StrongBox quando houver).
  Alias do Keystore nunca recriado em silêncio.
- **PIN errado nunca apaga** a carteira: só aumenta a espera (até 24 h). A tentativa é gravada antes de conferir.
- **Digital** com chave biométrica por uso; PIN obrigatório após reinício, erro, prazo e para frase/exportação.
- **Só Mainnet**, servidor conferido pelo hash genesis; envio para todos os servidores; “expirado” só com prova de
  dois servidores (evita pagamento duplo).
- **Token-2022** com extensões perigosas negado por padrão.
- **Janela protegida**: `FLAG_SECURE`, toques filtrados com sobreposição, janelas sobrepostas escondidas; backup do
  Android desligado; sem tráfego em claro.
- **Sem logs, telemetria, analytics ou servidor próprio.** O app fala só com RPCs públicos da Solana (ou o seu) e
  com a cotação pública do SOL (Coinbase/Kraken).
- **Cadeia de fornecimento**: versões fixas, hashes de dependências verificados pelo Gradle, SBOM CycloneDX por release.

Detalhes: [`SECURITY.md`](SECURITY.md), [`docs/RELATORIO-SEGURANCA.md`](docs/RELATORIO-SEGURANCA.md),
[`docs/threat-model.md`](docs/threat-model.md), [`docs/adr/`](docs/adr/).

### Limites conhecidos

- O código passou por quatro rodadas internas de ataque, com testes automáticos para cada cenário; **não houve
  auditoria externa independente**.
- Os testes rodam em Android simulado (Robolectric); a lista de verificação em aparelho físico está em
  `docs/RELEASE-CHECKLIST.md`. Comece com valores pequenos.
- Um aparelho com root ou sistema comprometido pode ler a tela e controlar o app; nenhuma carteira de celular elimina
  esse risco. Os sinais de root/hook são informativos.
- Com a digital ativada, alguém que force seu dedo pode assinar dentro do prazo escolhido (não pode ver a frase nem
  exportar). Use prazo curto, “Bloquear agora” ou deixe a digital desligada.
- Servidores RPC podem mostrar saldo errado se combinarem a mentira (não podem assinar nem mover fundos).
- O retorno estimado do stake não inclui comissão de MEV (a rede não informa esse dado por RPC).

### Compilar a partir do código

Requisitos: JDK 17, Android SDK (`platforms;android-36`, `build-tools;36.0.0`), Python 3.

```
git clone https://github.com/ChristopherKirstusCamargo/LockWallet.git
cd LockWallet
echo "sdk.dir=/caminho/para/Android/Sdk" > local.properties
./gradlew ciCheck                 # testes + guardas de arquitetura e de APIs proibidas
./gradlew :app:assembleRelease    # APK release (sem assinatura, se não houver chave)
```

Se `./gradlew` não estiver executável, rode `chmod +x gradlew`. Se `gradle/wrapper/gradle-wrapper.jar` não estiver
presente, gere-o com um Gradle 8.14.3 instalado:
`gradle wrapper --gradle-version 8.14.3` (a distribuição é conferida pelo `distributionSha256Sum`).

Assinatura: crie um `signing.properties` **fora do repositório** com `storeFile`, `storePassword`, `keyAlias`,
`keyPassword` e passe `-Plockwallet.signingProps=/caminho/signing.properties`. Um build seu terá outro certificado:
o Android não o instala por cima do APK oficial.

Vetores independentes: `python -m venv v && v/bin/pip install -r tools/oracle/requirements.txt &&
v/bin/python tools/oracle/generate_fixtures.py fixtures/vectors && git diff --exit-code fixtures`.

### Relatar vulnerabilidades

Em privado, conforme [`SECURITY.md`](SECURITY.md). Não abra issue pública com detalhes exploráveis.

---

## English

### Why Lock Wallet exists

Lock Wallet started with a decision by Christopher: to have **his own serious, security-first,
self-custody Solana wallet**, built from scratch and meant to be taken more seriously than his earlier portfolio
tracker, CoinPortracker. The principles were fixed before the first line of code: **Solana only**, **no blind
signing** (every transaction decoded, analyzed and simulated before you approve), **native staking only** (never
liquid staking), **no telemetry, no logs, no backend**, and a clean **black-and-white glass design** with
explanations only when you ask ("?" icon). The project follows a master specification and is documented in ADRs,
a threat model and a security report (Portuguese).

### Features

- Create or restore with a 12/24-word phrase (BIP-39, SLIP-0010 `m/44'/501'/n'/0'`), private-key import, multiple
  accounts and wallets.
- Local custody encrypted with Argon2id + AES-GCM under an Android Keystore key, or **Seed Vault** on Solana Mobile
  devices (the seed never enters the app).
- Receive (QR + full address in blocks); send SOL and SPL / Token-2022 tokens (dangerous extensions denied by
  default) with exact fee, simulation and PIN confirmation.
- **Total balance** = available SOL + all your staked SOL, with an *Available / Staked* breakdown and last-epoch reward
  when the chain reports it consistently; values in 10 fiat currencies.
- **Native staking** with 6 curated validators checked live on-chain and ordered **safety first, then estimated
  return** (effective commission and on-chain performance); Advanced mode with full list and warnings.
- **dApps (Lock Connect)**: Mobile Wallet Adapter 2.x, local association only, per-dApp sessions, every request
  analyzed.
- **Backup**: recovery phrase and encrypted Recovery Package (`.lwrp`) with a generated 8-word password.
- Optional **biometric unlock** with mandatory PIN after reboot, after a PIN error, and every 1/4/24/72 h (default
  24 h), plus a **"Lock now"** action.

### Install from the APK

1. Download `lockwallet.apk`.
2. Verify the file hash: `sha256sum lockwallet.apk` (Windows: `Get-FileHash lockwallet.apk -Algorithm SHA256`) must
   equal `1833c24e0ffa74bb2b07615493b03f9c7c294ba3acd83ca6fb039e230f43be09`.
3. Verify the signing certificate: `apksigner verify --print-certs lockwallet.apk` — `Signer #1 certificate SHA-256
   digest` must equal `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b`.
4. Install (allow installs from the app you used to open it, then turn it off again) or `adb install lockwallet.apk`.

### Using it

- **Create / restore**: *Criar uma carteira* or *Adicionar uma carteira existente* → choose this device or Seed Vault
  → write the words on paper → set a 6-digit PIN.
- **Receive**: Home → *Receber* → show the QR or share the address.
- **Send**: Home → *Enviar* → address and amount (comma as decimal separator) → review destination, amount, fee and
  warnings → confirm with PIN. *Max* uses only the available balance, never stake.
- **Stake**: Stake tab → *Novo stake* → pick a recommended validator (or Advanced) → amount → review → confirm. You
  always remain the stake and withdraw authority. To exit: *Desativar*, then *Sacar* after the epoch ends.
- **dApps**: connect from a Solana dApp on the same phone via Mobile Wallet Adapter; review every request; revoke
  sessions in the dApps tab.
- **Backup**: Settings → Wallets → your wallet → *Ver frase de recuperação* / *Exportar Recovery Package* (PIN
  required).

### Security model

What you see is exactly what is signed (reviewed hash = signed hash); closed instruction allowlist; mandatory
simulation; priority fee above 0.005 SOL blocked; keys never leave the device; wrong PINs never wipe (escalating delay
up to 24 h, attempt recorded before checking); mainnet only, RPC pinned by genesis hash; transactions broadcast to
all RPCs and only declared expired with evidence from two RPCs; `FLAG_SECURE`, overlay protection, Android backup
disabled, no cleartext traffic; no logs, telemetry, analytics or backend; pinned dependency hashes and a CycloneDX
SBOM per release. See [`SECURITY.md`](SECURITY.md) and `docs/`.

### Known limits

Four internal adversarial review rounds with automated tests for every scenario; **no independent external audit**.
Tests run on simulated Android (Robolectric); the physical-device checklist is in `docs/RELEASE-CHECKLIST.md` —
start with small amounts. A rooted or compromised device can read the screen and drive the app. With biometrics on,
a coerced fingerprint can sign within the chosen window (it cannot reveal the phrase or export). Colluding RPCs can
display a wrong balance (they cannot sign or move funds). The staking return estimate excludes MEV commission, which
standard RPC does not expose.

### Build from source

JDK 17, Android SDK (`platforms;android-36`, `build-tools;36.0.0`), Python 3. Create `local.properties` with
`sdk.dir=...`, then `./gradlew ciCheck` and `./gradlew :app:assembleRelease` (`chmod +x gradlew` first if needed). If `gradle/wrapper/gradle-wrapper.jar` is
missing, run `gradle wrapper --gradle-version 8.14.3`. Signing uses a `signing.properties` file kept outside the repo
(`-Plockwallet.signingProps=...`); your own build has a different certificate and will not install over the official
APK.

### License

[Apache-2.0](LICENSE) — see also [NOTICE](NOTICE).
