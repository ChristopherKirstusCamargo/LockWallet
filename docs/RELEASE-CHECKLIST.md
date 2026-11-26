# Checklist de release

O projeto está encerrado na versão final, publicada como “Lock Wallet”. Este roteiro existe para conferência da versão publicada e para o caso
excepcional de uma correção extraordinária assinada com a mesma chave.

Pré-requisitos: JDK 17 ou mais novo, Android SDK (`platforms;android-36`, `build-tools;36.0.0`, NDK 27.2.12479018),
Rust conforme `rust-toolchain.toml` com `cargo-ndk`, Python 3. Nos comandos, `$BT` é `$ANDROID_HOME/build-tools/36.0.0`.

## 1. Código

- [ ] Incrementar `versionCode` (e o `versionName` interno) em `app/build.gradle.kts`. A interface e a Release não
      mostram número de versão.
- [ ] `./gradlew ciCheck` e `cargo test --locked --manifest-path native/privacy-ring/Cargo.toml --lib` verdes.
- [ ] Mudança de dependência: `./gradlew --write-verification-metadata sha256 ciCheck` e revisar o diff de
      `gradle/verification-metadata.xml`.
- [ ] Threat model, revisão de segurança e CHANGELOG atualizados.

## 2. Chave de assinatura

- A chave de produção fica **offline**, fora do repositório, do GitHub e do CI. Certificado:
  `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b`.
- `signing.properties` (fora do repositório) contém `storeFile`, `storePassword`, `keyAlias`, `keyPassword`,
  `storeType`. Nunca versionar `.p12`, `.p12.enc`, `.jks`, `signing.properties` ou `local.properties`.
- Nunca assinar no CI e nunca gerar chave nova: uma chave diferente impede a atualização no lugar.

## 3. Build

```
./gradlew -Plockwallet.signingProps=/caminho/fora/do/repo/signing.properties :app:assembleRelease
```

Saída: `app/build/outputs/apk/release/app-release.apk`. Sem `-P…` o release sai sem assinatura (só para inspeção).

## 4. Verificação

- [ ] `$BT/apksigner verify --verbose --print-certs --min-sdk-version 24 app-release.apk`: v2 e v3 `true`, um signatário,
      certificado igual ao da seção 2.
- [ ] `python3 tools/verify_apk_manifest.py --aapt2 "$BT/aapt2" --apk app-release.apk --release`: OK.
- [ ] `$BT/aapt2 dump badging app-release.apk`: pacote `com.lockwallet.app`, `versionCode` e `versionName` corretos.
- [ ] `$BT/aapt2 dump permissions`: nenhuma permissão nova sem justificativa.
- [ ] Renomear para `LockWallet.apk` e gerar `sha256sum LockWallet.apk > LockWallet.apk.sha256`.
- [ ] SBOM opcional: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath -q > /tmp/deps.txt` e
      `python3 tools/sbom.py /tmp/deps.txt --version <versão> --apk-sha256 <sha> -o LockWallet.cdx.json`.

## 5. Varredura antes do commit

- [ ] `git status` sem `local.properties`, keystore, `signing.properties` ou APK.
- [ ] `git grep -nIE "BEGIN (RSA |EC )?PRIVATE KEY|ghp_|github_pat_|storePassword *=|keyPassword *="` só com as linhas de
      `app/build.gradle.kts` que leem o arquivo e com esta própria linha.

## 6. Teste manual mínimo (aparelho real, valores pequenos)

- [ ] Instalação limpa; criar carteira; anotar a frase; bloquear e desbloquear; PIN errado 3× (nada é apagado).
- [ ] Atualizar uma instalação assinada com a mesma chave (`adb install -r`): carteiras, PIN, moeda e tema mantidos.
- [ ] Receber e enviar uma quantia pequena de SOL; conferir taxa e explorador.
- [ ] Stake: delegar o mínimo e desativar.
- [ ] Proteções de envio: adicionar um contato, ligar “Somente contatos” e um limite diário; um envio fora das regras é recusado; afrouxar pede o PIN e fica pendente por 24 h.
- [ ] Fotografia GIF: animação aparece, para com “Animações” desligado e ao sair do app.
- [ ] Bloqueio por inatividade no prazo escolhido.
- [ ] Captura de tela bloqueada (`FLAG_SECURE`); conteúdo oculto na lista de recentes.

## 7. Publicação

- [ ] Uma Release `v<versão>` com o APK e o `.sha256`, notas em pt-BR com SHA-256 e certificado.
- [ ] Conferir o asset baixado: SHA-256 e certificado iguais aos locais.
