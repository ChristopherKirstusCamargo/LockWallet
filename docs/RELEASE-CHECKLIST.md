# Checklist de release

Pré-requisitos: `source /home/box/tools/env.sh` (JDK 17, SDK 36, build-tools com `apksigner`).

## 1. Código
- [ ] `versionName` permanece `Final`; incrementar `versionCode` em `app/build.gradle.kts` a cada APK publicado para que o Android aceite a atualização.
- [ ] `./gradlew --write-verification-metadata sha256 ciCheck` verde; revisar o diff de
      `gradle/verification-metadata.xml` (nenhum hash novo sem dependência nova justificada em ADR).
- [ ] Nenhuma ocorrência de endpoints de rede de teste/faucet, `Log`, `println`, `printStackTrace` (verifyForbiddenApis).
- [ ] Threat model e errata atualizados para mudanças de comportamento.

## 2. Chave de assinatura (uma vez por app; nunca reutilizar chave de outro app)
- Diretório **fora do repositório**: `/workspace/lockwallet-signing/` (`chmod 700`).
- `lockwallet-release.p12` (PKCS12, RSA 4096, validade 30 anos, alias `lockwallet`), `signing.properties` (`chmod 600`)
  com `storeFile`, `storePassword`, `keyAlias`, `keyPassword`, `storeType`. Senhas geradas com `openssl rand`,
  nunca impressas, nunca commitadas. Faça backup cifrado offline do keystore e das senhas: perder a chave impede
  atualizar o app instalado.

## 3. Build
```
./gradlew -Plockwallet.signingProps=/workspace/lockwallet-signing/signing.properties :app:assembleRelease
```
Saída: `app/build/outputs/apk/release/app-release.apk`. Sem `-P…` o release sai **não assinado**; use-o apenas para inspeção local.

## 4. Verificação
- [ ] `apksigner verify --verbose --print-certs --min-sdk-version 24 app-release.apk` → v2 **e** v3 `true`; anotar
      SHA-256 do certificado. (Sem `--min-sdk-version` o apksigner só relata o esquema exigido pelo minSdk 31, e v2
      aparece como `false` mesmo presente.)
- [ ] `python3 tools/verify_apk_manifest.py --aapt2 "$BT/aapt2" --apk app-release.apk --release` → OK (backup off, sem cleartext,
      permissões e componentes exportados esperados).
- [ ] `versionName`/`versionCode` conferidos com `aapt2 dump badging`.
- [ ] Copiar para `dist/LockWallet-<versão>.apk`, gerar `sha256sum` (`.sha256`) e SBOM:
```
./gradlew :app:dependencies --configuration releaseRuntimeClasspath -q > /tmp/deps.txt
python3 tools/sbom.py /tmp/deps.txt --version <versão> --apk-sha256 <sha> -o dist/LockWallet-<versão>.cdx.json
```

## 5. Teste manual mínimo (aparelho real, **Mainnet com valores pequenos**)
- [ ] 4.0.0 **atualiza** a 3.0.0 instalada (`adb install -r`): carteiras, PIN, biometria, moeda e tema mantidos.
- [ ] Novo stake: bloco com os validadores recomendados e números ao vivo; “Avançado” mostra aviso, lista e colar endereço.
- [ ] Vidro: folhas transparentes com fundo desfocado; textos legíveis nos dois temas.
- [ ] Biometria: ligar nos Ajustes (pede PIN); bloquear e abrir com a digital; cancelar ⇒ teclado do PIN; reiniciar ⇒
      PIN; PIN errado ⇒ PIN; cadastrar digital nova ⇒ aviso e PIN; ver frase/exportar continuam pedindo PIN.
- [ ] Exportar LWRP com senha gerada: as 8 palavras aparecem inteiras (fonte grande também); restaurar o arquivo.
- [ ] Trocar a moeda entre as 10; saldo zero não mostra variação.
- [ ] Instalar limpo; criar carteira; anotar frase; bloquear/desbloquear; PIN errado 3× (sem apagar).
- [ ] Receber ~0,02 SOL de outra carteira; enviar 0,001 SOL a outro endereço (digitado e via QR); conferir taxa e explorer.
- [ ] Token: enviar um token pequeno (com criação de ATA); staking: delegar o mínimo e desativar.
- [ ] Lock Connect com um dApp em Mainnet: conectar, assinar mensagem, revogar sessão (pedido de Devnet deve ser recusado).
- [ ] Capturas de tela bloqueadas (FLAG_SECURE); app some da lista de recentes com conteúdo oculto.

## 6. Estado atual da publicação

- A Release `v5.0.2` e seus artefatos publicados devem permanecer preservados. Não substituir nem apagar o APK já distribuído.
- A automação de assinatura/publicação do GitHub Actions foi aposentada. `.github/workflows/build-apk.yml` está desativado para
  assinatura e publicação; não restaurar esse fluxo nem reutilizar credenciais de CI.
- O Secret legado `LOCKWALLET_KEYSTORE_PASSWORD` deve ser removido manualmente em
  **Settings → Secrets and variables → Actions**. O conector de repositório não administra valores de Secrets.
- A chave privada e sua senha devem ficar sob custódia offline, fora do GitHub e do ambiente de CI. Nunca versionar
  `.p12`, `.p12.enc`, `signing.properties` ou arquivos equivalentes. O `.gitignore` bloqueia esses caminhos para
  reduzir reincidência.
- Uma futura publicação exige revisão independente do código e da cadeia de build, assinatura em ambiente controlado,
  conferência do fingerprint do certificado específico daquela versão e publicação do SHA-256/SBOM.
