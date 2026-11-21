# Lock Wallet

**Carteira Solana de autocustódia para Android, projetada em torno da segurança.**
*Self-custody Solana wallet for Android, designed around security.*

[Português](#português) · [English](#english)

| | |
|---|---|
| Plataforma | Android 12+ (API 31) |
| Rede | Solana Mainnet |
| Pacote | `com.lockwallet.app` |
| Versão pública | Final (a Release é atualizada a cada publicação) |
| SHA-256 do APK | Confira o hash informado na descrição da Release final |
| SHA-256 do certificado | `ed4c0025c0b4d551fa95945c89d3d24e504349a2fab289ff90de66409fff8c13` |
| Licença | Apache-2.0 |

---

## Português

### Visão geral

A Lock Wallet é uma carteira de autocustódia exclusiva para Solana. Suas chaves ficam no seu aparelho, toda
transação é decodificada e simulada antes da aprovação, e o stake é sempre nativo. Não há servidor próprio,
telemetria, anúncios nem cadastro.

### Motivação e princípios

A Lock Wallet nasceu para ser uma carteira Solana construída do zero com a segurança como critério principal de cada
decisão. Os princípios do projeto:

- **Só Solana.** Sem pontes nem outras redes; menos superfície de ataque.
- **Nada é assinado às cegas.** O que você revisa é exatamente o que é assinado.
- **Só stake nativo.** Nunca stake líquido, pools ou tokens de recibo; a autoridade é sempre sua.
- **Sem telemetria, sem logs, sem backend.** O app fala apenas com a rede Solana e com cotações públicas do SOL.
- **Clareza.** Design monocromático em vidro, direto ao ponto; explicações sob demanda no ícone “?”.

O desenvolvimento segue uma especificação mestre, com decisões registradas em [`docs/adr/`](docs/adr/), modelo de
ameaças em [`docs/threat-model.md`](docs/threat-model.md) e relatório de segurança em
[`docs/RELATORIO-SEGURANCA.md`](docs/RELATORIO-SEGURANCA.md).

### Destaques

- **Carteiras:** criar ou restaurar com frase de 12/24 palavras (BIP-39, SLIP-0010 `m/44'/501'/n'/0'`), importar
  chave privada, várias contas e carteiras.
- **Custódia:** cofre local cifrado (Argon2id + AES-GCM, chave no Android Keystore/StrongBox) ou **Seed Vault** em
  aparelhos Solana Mobile — a semente nunca entra no app.
- **Envio e recebimento** de SOL e tokens SPL/Token-2022, com taxa exata, simulação e confirmação por PIN.
- **Saldo total** com o SOL em stake incluído e detalhe *Disponível / Em stake*; valores em 10 moedas.
- **Stake nativo** com 6 validadores recomendados, conferidos on-chain e ordenados por segurança e depois por retorno
  estimado; modo Avançado com a lista completa. Desativar, sacar, dividir e juntar contas de stake.
- **dApps** via Mobile Wallet Adapter 2.x (Lock Connect), com cada pedido analisado antes da aprovação.
- **Backup** pela frase de recuperação e pelo Recovery Package (`.lwrp`) cifrado com senha de 8 palavras.
- **Digital opcional para desbloqueio de visualização** com prazo máximo sem PIN configurável (1/4/24/72 h, padrão 24 h) e ação **Bloquear agora**. Toda assinatura exige PIN.
- **Central de Segurança** com as proteções que o aparelho realmente oferece (StrongBox/TEE, biometria, sinais de
  root/depurador).
- Tema escuro e claro; sem anúncios, sem conta, sem e-mail.

### Instalação

1. Baixe o único arquivo `lockwallet.apk` na [Release oficial](https://github.com/ChristopherKirstusCamargo/LockWallet/releases/latest) ou pelo [link direto](https://github.com/ChristopherKirstusCamargo/LockWallet/releases/latest/download/lockwallet.apk).
2. Confira o SHA-256 do arquivo com o hash informado na descrição da Release.
3. Confira o certificado com `apksigner verify --print-certs lockwallet.apk`. O SHA-256 esperado do certificado atual é `ed4c0025c0b4d551fa95945c89d3d24e504349a2fab289ff90de66409fff8c13`.

**Migração de instalações antigas:** se o Android informar incompatibilidade de assinatura ao atualizar, a instalação anterior foi assinada por outro certificado. Antes de desinstalar a versão antiga, faça backup da frase de recuperação ou do Recovery Package e confirme que consegue restaurá-lo. Não desinstale antes de verificar o backup; nunca compartilhe as palavras ou o pacote de recuperação.

### Como usar

**Criar ou restaurar**
1. Toque em **Criar uma carteira** ou em **Adicionar uma carteira existente**.
2. Escolha onde a chave fica: **neste aparelho** ou no **Seed Vault**, quando disponível.
3. Ao criar, anote as palavras no papel, confirme o teste de palavras e defina um PIN de 6 dígitos.
4. Ao restaurar, digite a frase (teclado de senha; a área de transferência é limpa ao colar), escolha a conta e
   defina o PIN.

**Receber**
1. **Início → Receber.**
2. Mostre o QR ou compartilhe o endereço, conferindo-o por inteiro.

**Enviar**
1. **Início → Enviar**: cole ou leia (QR) o endereço e informe o valor, com vírgula decimal (ex.: `1,5`).
2. Na revisão, confira destino completo, valor, taxa e avisos.
3. Confirme com PIN. A digital só desbloqueia a visualização; não autoriza assinaturas. **Máximo** usa somente o saldo *Disponível*.

**Stake**
1. **Stake → Novo stake.**
2. Escolha um validador recomendado (o “?” explica a ordem) ou use **Avançado**.
3. Informe o valor, revise e confirme. A conta de stake é sua; você é sempre a autoridade.
4. Para retirar: **Desativar** e, ao fim da época, **Sacar**.

**dApps**
1. Em um dApp Solana no mesmo aparelho, conecte uma carteira via Mobile Wallet Adapter e escolha a Lock Wallet.
2. Revise cada transação ou mensagem exibida de forma decodificada. Redes de teste, taxas abusivas e instruções fora
   da lista permitida são recusadas.
3. Revogue sessões na aba **dApps**.

**Backup**
1. **Ajustes → Carteiras →** selecione a carteira.
2. **Ver frase de recuperação** (exige PIN): guarde no papel, nunca em foto.
3. **Exportar Recovery Package** (exige PIN): arquivo `.lwrp` cifrado com senha de 8 palavras gerada na hora.
   Guarde arquivo e senha em lugares diferentes.

**Proteção no dia a dia**
- **Ajustes → Desbloquear com a digital:** define o prazo máximo sem PIN.
- **Ajustes → Bloquear agora:** bloqueia o app e exige o PIN no próximo desbloqueio.

### Modelo de segurança

- **Assinatura consciente:** o hash revisado é o hash assinado; instruções fora da lista fechada são recusadas ou
  exibidas cruas com aviso forte.
- **Simulação obrigatória** antes de assinar; taxa de prioridade acima de 0,005 SOL bloqueada.
- **Chaves só no aparelho:** semente cifrada com Argon2id + AES-GCM e chave do Keystore (StrongBox quando houver);
  a chave do Keystore nunca é recriada silenciosamente.
- **PIN errado nunca apaga a carteira:** a espera cresce até 24 h; cada tentativa é gravada antes da conferência.
- **Digital:** chave biométrica por uso, limitada ao desbloqueio de visualização; toda assinatura, revelação da frase,
  exportação e operação sensível continua exigindo PIN, com verificação também na camada de custódia.
- **Rede:** só Mainnet, servidor conferido pelo hash genesis; envio para todos os servidores; transação só é dada
  como expirada com evidência de dois servidores.
- **Tokens:** extensões Token-2022 perigosas negadas por padrão.
- **Plataforma:** `FLAG_SECURE`, filtro de toques sob sobreposição, janelas sobrepostas ocultas, backup do Android
  desativado, sem tráfego em claro.
- **Privacidade:** sem logs, telemetria, analytics ou backend.
- **Cadeia de fornecimento:** versões fixas, hashes de dependências verificados pelo Gradle e SBOM CycloneDX por
  release.

Detalhes em [`SECURITY.md`](SECURITY.md) e [`docs/`](docs/).

### Limites conhecidos

- Quatro rodadas internas de ataque, com teste automático para cada cenário; não houve auditoria externa independente.
- A suíte automatizada roda em Android simulado (Robolectric); a verificação em aparelho físico está em
  [`docs/RELEASE-CHECKLIST.md`](docs/RELEASE-CHECKLIST.md). Recomenda-se começar com valores pequenos.
- Aparelho com root ou sistema comprometido pode ler a tela e controlar o app; sinais de root/hook são informativos.
- Com a digital ativada, uma digital obtida sob coação pode abrir a visualização de saldos durante o prazo escolhido,
  mas não autoriza assinaturas nem a exportação da frase/backup; essas operações exigem PIN. Um aparelho com root ou
  sistema comprometido continua fora dessa garantia.
- Servidores RPC em conluio podem exibir saldo incorreto; não podem assinar nem mover fundos.
- O retorno estimado do stake não inclui comissão de MEV, que o RPC padrão não informa.

### Compilar a partir do código

Requisitos: JDK 17, Android SDK (`platforms;android-36`, `build-tools;36.0.0`), Python 3.

```
git clone https://github.com/ChristopherKirstusCamargo/LockWallet.git
cd LockWallet
echo "sdk.dir=/caminho/para/Android/Sdk" > local.properties
./gradlew ciCheck                 # testes e guardas de arquitetura
./gradlew :app:assembleRelease    # APK release
```

- O Gradle Wrapper baixa o Gradle 8.14.3 e confere a distribuição por `distributionSha256Sum`; o CI também confere
  o SHA-256 do `gradle-wrapper.jar`.
- Assinatura: crie `signing.properties` fora do repositório (`storeFile`, `storePassword`, `keyAlias`,
  `keyPassword`) e use `-Plockwallet.signingProps=/caminho/signing.properties`. Builds próprios têm outro
  certificado e não substituem o APK oficial.
- Vetores independentes: `python -m venv v && v/bin/pip install -r tools/oracle/requirements.txt &&
  v/bin/python tools/oracle/generate_fixtures.py fixtures/vectors && git diff --exit-code fixtures`.

### Segurança e contato

Vulnerabilidades devem ser relatadas em privado, conforme [`SECURITY.md`](SECURITY.md).

### Licença

[Apache-2.0](LICENSE) · [NOTICE](NOTICE)

---

## English

### Overview

Lock Wallet is a Solana-only self-custody wallet. Keys stay on your device, every transaction is decoded and
simulated before approval, and staking is always native. There is no backend, telemetry, advertising or sign-up.

### Motivation and principles

Lock Wallet was built to be a Solana wallet designed from scratch with security as the deciding factor in every
decision. Project principles:

- **Solana only.** No bridges or other chains; a smaller attack surface.
- **No blind signing.** What you review is exactly what gets signed.
- **Native staking only.** Never liquid staking, pools or receipt tokens; you always remain the authority.
- **No telemetry, no logs, no backend.** The app talks only to the Solana network and public SOL price feeds.
- **Clarity.** A monochrome glass design that gets to the point, with explanations on demand behind the "?" icon.

Development follows a master specification, with decisions recorded in [`docs/adr/`](docs/adr/), a threat model in
[`docs/threat-model.md`](docs/threat-model.md) and a security report (Portuguese) in
[`docs/RELATORIO-SEGURANCA.md`](docs/RELATORIO-SEGURANCA.md).

### Highlights

- **Wallets:** create or restore from a 12/24-word phrase (BIP-39, SLIP-0010 `m/44'/501'/n'/0'`), private-key import,
  multiple accounts and wallets.
- **Custody:** encrypted local vault (Argon2id + AES-GCM under an Android Keystore/StrongBox key) or **Seed Vault** on
  Solana Mobile devices — the seed never enters the app.
- **Send and receive** SOL and SPL/Token-2022 tokens with exact fees, simulation and PIN confirmation.
- **Total balance** including staked SOL, with an *Available / Staked* breakdown; values in 10 fiat currencies.
- **Native staking** with 6 recommended validators, verified on-chain and ordered by safety, then estimated return;
  Advanced mode with the full list. Deactivate, withdraw, split and merge stake accounts.
- **dApps** via Mobile Wallet Adapter 2.x (Lock Connect), with every request analyzed before approval.
- **Backup** through the recovery phrase and an encrypted Recovery Package (`.lwrp`) with an 8-word password.
- **Optional view-only biometrics** with a configurable maximum PIN-free window (1/4/24/72 h, default 24 h) and a
  **Lock now** action. Every signature still requires the wallet PIN.
- **Security Center** showing the protections the device actually provides (StrongBox/TEE, biometrics, root/debugger
  signals).
- Dark and light themes; no ads, no account, no e-mail.

### Installation

1. Download the only file, `lockwallet.apk`, from the [official Release](https://github.com/ChristopherKirstusCamargo/LockWallet/releases/latest) or the [direct download link](https://github.com/ChristopherKirstusCamargo/LockWallet/releases/latest/download/lockwallet.apk).
2. Compare the APK's SHA-256 with the hash shown in the Release description.
3. Verify the certificate with `apksigner verify --print-certs lockwallet.apk`. The expected SHA-256 certificate fingerprint is `ed4c0025c0b4d551fa95945c89d3d24e504349a2fab289ff90de66409fff8c13`.

**Migrating older installations:** if Android reports a signature mismatch during an update, the previous installation was signed with a different certificate. Before uninstalling it, back up the recovery phrase or Recovery Package and confirm that it can be restored. Never uninstall before verifying the backup, and never share recovery words or the package.

### Usage

- **Create / restore:** *Criar uma carteira* or *Adicionar uma carteira existente* → choose this device or Seed Vault →
  write the words on paper → set a 6-digit PIN.
- **Receive:** Home → *Receber* → show the QR code or share the full address.
- **Send:** Home → *Enviar* → address and amount (comma as decimal separator) → review destination, amount, fee and
  warnings → confirm. *Max* uses only the available balance.
- **Stake:** Stake → *Novo stake* → pick a recommended validator or use Advanced → amount → review → confirm. To exit:
  *Desativar*, then *Sacar* after the epoch ends.
- **dApps:** connect from a Solana dApp on the same device via Mobile Wallet Adapter, review every request, revoke
  sessions in the dApps tab.
- **Backup:** Settings → Wallets → select the wallet → *Ver frase de recuperação* or *Exportar Recovery Package*
  (PIN required).
- **Everyday protection:** Settings → biometric window; Settings → *Bloquear agora* to require the PIN immediately.

### Security model

- **Informed signing:** the reviewed hash is the signed hash; instructions outside the closed allowlist are refused or
  shown raw behind a strong warning.
- **Mandatory simulation** before signing; priority fees above 0.005 SOL are blocked.
- **Keys stay on the device:** seed encrypted with Argon2id + AES-GCM under a Keystore key (StrongBox when present);
  the Keystore key is never silently recreated.
- **Wrong PINs never wipe the wallet:** the delay grows up to 24 h; each attempt is recorded before it is checked.
- **Biometrics:** per-use biometric key for view unlock only; every transaction, dApp/message signature, phrase reveal
  and backup export still requires the wallet PIN, enforced inside the custody layer.
- **Network:** Mainnet only, RPC pinned by genesis hash; broadcast to every RPC; a transaction is declared expired only
  with evidence from two RPCs.
- **Tokens:** dangerous Token-2022 extensions denied by default.
- **Platform:** `FLAG_SECURE`, overlay touch filtering, hidden overlay windows, Android backup disabled, no cleartext
  traffic.
- **Privacy:** no logs, telemetry, analytics or backend.
- **Supply chain:** pinned versions, Gradle-verified dependency hashes and a CycloneDX SBOM per release.

See [`SECURITY.md`](SECURITY.md) and [`docs/`](docs/).

### Known limits

- Four internal adversarial review rounds, each scenario covered by an automated test; no independent external audit.
- The automated suite runs on simulated Android (Robolectric); physical-device verification is listed in
  [`docs/RELEASE-CHECKLIST.md`](docs/RELEASE-CHECKLIST.md). Starting with small amounts is recommended.
- A rooted or compromised device can read the screen and drive the app; root/hook signals are informational.
- With biometrics enabled, a coerced fingerprint can open the portfolio view within the selected window, but cannot
  authorize signatures or reveal/export recovery secrets; those actions require the wallet PIN. A rooted or otherwise
  compromised operating system remains outside this guarantee.
- Colluding RPC servers can display an incorrect balance; they cannot sign or move funds.
- The staking return estimate excludes MEV commission, which standard RPC does not expose.

### Building from source

Requirements: JDK 17, Android SDK (`platforms;android-36`, `build-tools;36.0.0`), Python 3.

```
git clone https://github.com/ChristopherKirstusCamargo/LockWallet.git
cd LockWallet
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew ciCheck
./gradlew :app:assembleRelease
```

The Gradle Wrapper downloads Gradle 8.14.3 and checks it against `distributionSha256Sum`. Signing uses a
`signing.properties` file kept outside the repository (`-Plockwallet.signingProps=...`); self-built APKs carry a
different certificate and do not replace the official release.

### Security contact

Report vulnerabilities privately as described in [`SECURITY.md`](SECURITY.md).

### License

[Apache-2.0](LICENSE) · [NOTICE](NOTICE)
