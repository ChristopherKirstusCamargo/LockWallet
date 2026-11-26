# Lock Wallet

**Carteira Solana de autocustódia para Android, construída em torno da segurança.**

[Português](#português) · [English](#english)

| | |
|---|---|
| Versão | **Lock Wallet** (versão final), 26/11/2026 |
| Download | [Release “Lock Wallet”](https://github.com/ChristopherKirstusCamargo/LockWallet/releases/latest) |
| APK SHA-256 | `0aa19fd3919557ed9bc8ff69c8626bf093bf11773ea3f218283f110d1a703449` |
| Certificado (SHA-256) | `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b` |
| Pacote | `com.lockwallet.app` · Android 12 ou mais novo (API 31+) |
| Licença | [Apache-2.0](LICENSE) |

> [!IMPORTANT]
> **Estado do projeto: encerrado.** Esta é a versão final e não há atualizações planejadas. A chave de
> assinatura é mantida offline pelo responsável pelo projeto e só seria usada numa correção extraordinária de uma
> falha grave. **Não houve auditoria externa independente.** O software é fornecido “como está”, sem garantias
> (veja a licença). Transações em blockchain são irreversíveis: comece com valores pequenos.

---

## Português

### Visão geral

A Lock Wallet é uma carteira de autocustódia só para Solana. As chaves ficam no aparelho, toda transação é decodificada
e simulada antes da aprovação, e o stake é sempre nativo. Não há servidor próprio, conta, telemetria nem anúncios.

Princípios:

- **Só Solana**, só Mainnet. Sem pontes nem outras redes.
- **Nada é assinado às cegas.** O que você revisa é exatamente o que é assinado.
- **Só stake nativo.** A autoridade da conta de stake é sempre sua.
- **Sem telemetria, logs ou backend.** O app fala apenas com a rede Solana e com cotações públicas do SOL.
- **Clareza.** Visual monocromático em um único material de vidro, com contraste verificado; explicações sob demanda
  no ícone “?”.

### Funcionalidades

- **Carteiras:** criar ou restaurar com frase de 12 ou 24 palavras (BIP-39 em inglês, SLIP-0010 `m/44'/501'/n'/0'`,
  sugestões locais acima do teclado), importar chave privada, várias carteiras e contas, renomear e excluir (com PIN).
- **Custódia:** cofre local cifrado (Argon2id + AES-GCM, chave no Android Keystore/StrongBox).
- **Enviar e receber** SOL e tokens SPL/Token-2022, com QR, taxa exata, simulação e confirmação por PIN.
- **Saldo total** com o SOL em stake incluído, em 10 moedas. Toque no saldo para alternar entre moeda e SOL; toque
  duas vezes para ocultar.
- **Stake nativo** com 6 validadores recomendados, conferidos on-chain, e modo Avançado com a lista completa.
  Desativar, sacar, dividir e juntar.
- **Saldo confidencial (Default Ring)**, na aba Privado: transferência de SOL com valor oculto; os endereços continuam
  públicos. Só Mainnet, só SOL, com endpoint Helius configurado por você.
- **Proteções de envio (opcionais):** contatos, “Somente contatos”, limite diário de SOL e confirmação de endereço novo.
  Afrouxar uma proteção exige o PIN e só vale 24 h depois.
- **Backup** pela frase e pelo Recovery Package (`.lwrp`) cifrado com senha de 8 palavras.
- **Fotografia de perfil** por carteira: JPEG, PNG, WebP, HEIF ou **GIF animado** (até 4 MB, 1024 px, 300 quadros;
  metadados removidos; pausa fora do primeiro plano e com “Animações” desligado). Sem permissão nova.
- **Ajustes** em uma lista curta de categorias, cada uma com a própria tela: Segurança, Moeda, Privacidade,
  Carteiras, Aparência e Sobre.
- **Valores grandes** em notação compacta a partir de 1 milhão (por exemplo, R$ 1,25 bi), com o valor exato no
  toque longo, no rótulo de acessibilidade e sempre na revisão do envio; “Completo” em Ajustes › Moeda desliga.
- **Digital opcional** só para desbloquear a visualização; bloqueio por inatividade (1/2/5/10 min); **Bloquear agora**.
- **Central de Segurança** com as proteções que o aparelho realmente oferece.

### Instalação e verificação

1. Baixe `LockWallet.apk` e `LockWallet.apk.sha256` da [Release “Lock Wallet”](https://github.com/ChristopherKirstusCamargo/LockWallet/releases/latest).
2. Confira o SHA-256:
   ```
   sha256sum -c LockWallet.apk.sha256
   ```
   Esperado: `0aa19fd3919557ed9bc8ff69c8626bf093bf11773ea3f218283f110d1a703449`.
3. Confira o certificado (Android SDK build-tools):
   ```
   apksigner verify --print-certs LockWallet.apk
   ```
   Esperado: `Signer #1 certificate SHA-256 digest: 3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b`.
4. Instale o APK. Qualquer outro certificado significa que o arquivo não é o oficial.

**Compatibilidade de atualização:**

- **Mesmo certificado:** a instalação atualiza no lugar, com carteiras e ajustes mantidos.
- **Outro certificado:** o Android recusa a atualização. Antes de desinstalar, anote a frase de recuperação ou exporte
  o Recovery Package e confirme que consegue restaurá-lo. Depois desinstale, instale o APK oficial e restaure. Nunca
  compartilhe a frase ou o pacote de recuperação.

### Modelo de segurança

- **Assinatura consciente:** o hash revisado é o hash assinado; instruções fora da lista fechada são recusadas.
  Simulação obrigatória antes de assinar.
- **Chaves só no aparelho:** semente cifrada com Argon2id + AES-GCM e chave do Keystore (StrongBox quando houver).
- **PIN errado nunca apaga a carteira:** a espera cresce até 24 h; cada tentativa é gravada antes da conferência.
- **PIN em toda assinatura:** a digital só abre a visualização; a camada de custódia recusa assinar sem PIN.
- **Rede:** só Mainnet, servidor conferido pelo genesis hash; envio para vários servidores; uma transação só é dada
  como expirada com evidência de dois servidores.
- **Endereços:** destino novo exige digitar os 4 últimos caracteres; aviso de endereço parecido com destinos
  anteriores e contatos; endereço copiado sai da área de transferência após 2 min.
- **Plataforma:** `FLAG_SECURE` sempre no release, toques sob sobreposição (total ou parcial) descartados, janelas
  sobrepostas ocultas, tela marcada como sensível para serviços de acessibilidade (Android 14+), backup do Android
  desligado, sem tráfego em claro, bloqueio ao sair do app e por inatividade.
- **Cadeia de fornecimento:** versões fixas, hashes de dependências verificados pelo Gradle, APK assinado fora do CI.

Detalhes: [`SECURITY.md`](SECURITY.md), [`docs/threat-model.md`](docs/threat-model.md),
[`docs/RELATORIO-SEGURANCA.md`](docs/RELATORIO-SEGURANCA.md) e
[`docs/REVISAO-SEGURANCA.md`](docs/REVISAO-SEGURANCA.md).

### Limites conhecidos

- **Sem auditoria externa independente.** As revisões foram internas, com teste automático para cada cenário.
- A suíte automatizada roda em Android simulado (Robolectric); não houve teste em aparelho físico com fundos reais
  neste ambiente.
- Aparelho com root ou sistema comprometido pode ler a tela e controlar o app; sinais de root/hook são informativos.
- Com a digital ligada, uma digital obtida sob coação abre a visualização de saldos durante o prazo escolhido, mas não
  autoriza assinaturas nem a exportação da frase.
- Servidores RPC em conluio podem mostrar saldo ou simulação errados; não podem assinar nem mover fundos.
- As proteções de envio protegem contra quem tem o aparelho desbloqueado e o PIN, não contra um sistema com root.
- O retorno estimado do stake não inclui MEV.
- Sem manutenção: falhas novas do Android, da rede Solana ou de dependências não serão acompanhadas.

### Arquitetura

Módulos `:app`, `:custody:local`, `:core:crypto`, `:core:solana`, `:data:rpc`, `:data:price` e a
ponte nativa Rust `native/privacy-ring`. Detalhes em [`docs/architecture.md`](docs/architecture.md) e nas decisões
em [`docs/adr/`](docs/adr/).

### Compilar, testar e publicar

Requisitos: JDK 17+, Android SDK (`platforms;android-36`, `build-tools;36.0.0`, NDK 27.2.12479018), Rust conforme
`rust-toolchain.toml` com `cargo-ndk`, Python 3.

```
git clone https://github.com/ChristopherKirstusCamargo/LockWallet.git
cd LockWallet
echo "sdk.dir=/caminho/para/Android/Sdk" > local.properties
./gradlew ciCheck                     # testes, guardas de arquitetura, lint e manifest do release
cargo test --locked --manifest-path native/privacy-ring/Cargo.toml --lib
./gradlew :app:assembleRelease        # APK release sem assinatura
```

- Assinatura: `-Plockwallet.signingProps=/caminho/signing.properties` (arquivo fora do repositório). Um build próprio
  tem outro certificado e não substitui o APK oficial.
- Variante de inspeção (captura de tela liberada, pacote `com.lockwallet.app.test`, só debug):
  `./gradlew -Plockwallet.testBuild=true -Plockwallet.inspectionBuild=true :app:assembleDebug`.
- Roteiro completo de release e verificação: [`docs/RELEASE-CHECKLIST.md`](docs/RELEASE-CHECKLIST.md).
- O workflow `.github/workflows/ci.yml` roda os testes. Nenhum workflow assina ou publica.

### Segurança e contato

Relate vulnerabilidades em privado, conforme [`SECURITY.md`](SECURITY.md). Histórico de mudanças em
[`CHANGELOG.md`](CHANGELOG.md).

---

## English

Lock Wallet is a Solana-only self-custody wallet for Android. Keys stay on the device, every transaction is decoded
and simulated before approval, staking is native only, and there is no backend, account, telemetry or advertising.

- **Status:** development has ended. The release published as **Lock Wallet** (2026-11-26) is the final version and
  no further updates are planned. The signing key is kept offline by the project owner and would be used only for an
  extraordinary fix of a serious flaw. **No independent external audit has been performed.** Start with small amounts.
- **Features:** BIP-39 wallets (12/24 words) and private-key import, local encrypted vault, SOL and SPL/Token-2022
  transfers, native staking with curated validators, confidential SOL balance (Default Ring, own tab), optional send
  protections (contacts, contacts-only mode, daily SOL limit, new-address confirmation; loosening needs the PIN and
  waits 24 h), encrypted Recovery Package, animated GIF profile photos, optional biometric unlock for viewing only,
  inactivity lock.
- **Install:** download `LockWallet.apk` from the [“Lock Wallet” Release](https://github.com/ChristopherKirstusCamargo/LockWallet/releases/latest). Check
  SHA-256 `0aa19fd3919557ed9bc8ff69c8626bf093bf11773ea3f218283f110d1a703449` and the signing certificate
  `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b` (`apksigner verify --print-certs`).
- **Upgrades:** installs signed with the same certificate update in place. Installs signed with another certificate
  cannot: back up and verify the recovery phrase or Recovery Package, uninstall, install the official APK and restore.
- **Build:** `./gradlew ciCheck`, `cargo test --locked --manifest-path native/privacy-ring/Cargo.toml --lib`,
  `./gradlew :app:assembleRelease`. See [`docs/RELEASE-CHECKLIST.md`](docs/RELEASE-CHECKLIST.md).
- **Security:** see [`SECURITY.md`](SECURITY.md). Report vulnerabilities privately through GitHub's
  “Report a vulnerability”.
- **License:** [Apache-2.0](LICENSE) · [NOTICE](NOTICE). The license does not grant use of the project name or visual
  identity to suggest an official connection.
