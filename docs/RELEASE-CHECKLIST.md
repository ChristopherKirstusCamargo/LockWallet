# Checklist de release

Pré-requisitos: `source /home/box/tools/env.sh` (JDK 17, SDK 36, build-tools com `apksigner`).

## 1. Código
- [ ] `versionName`/`versionCode` atualizados em `app/build.gradle.kts`; `CHANGELOG.md` com a versão.
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
Saída: `app/build/outputs/apk/release/app-release.apk`. Sem `-P…` o release sai **não assinado** (uso do CI).

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

## 6. Publicação automatizada
- A Release só é criada pelo workflow `.github/workflows/publish-release.yml` após `ciCheck`, build, assinatura e verificação do APK.
- Segredos obrigatórios no GitHub Actions: `LOCKWALLET_KEYSTORE_BASE64` e `LOCKWALLET_KEYSTORE_PASSWORD`. Nunca gravar o keystore ou a senha no repositório.
- Publicar `lockwallet.apk`, `lockwallet.apk.sha256`, `lockwallet-signing-certificate.pem`, verificação da assinatura e SBOM CycloneDX na página GitHub Releases.
- Fazer backup offline cifrado do `lockwallet-release.p12`; perder essa chave impede atualizações futuras com o mesmo certificado.

## Registro de releases
| Versão | Data | APK SHA-256 | Cert SHA-256 |
|---|---|---|---|
| 1.0.0 (1) | 2026-11-19 | `90f402a16e8a5000e5f763b77f3fb5986f9a037e6368f183c6117d5bcc520f34` | `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b` |
| 2.0.0 (2) | 2026-11-19 | `84b55e62e70a7c74ad1944985ee9d2660486912edcd583ed1eaf880a304717b5` | `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b` |
| 3.0.0 (3) | 2026-11-19 | `1861f89c1b531df8a5a1d28802e8d4884ea65684764fdec447ffad8f1926ca08` | `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b` |
| 4.0.0 (4) | 2026-11-19 | `f3f4a2e2fce3b8eada0a74c3ef53aa55f96f011c90b3357d35833b42bc470753` | `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b` |
| 5.0.0 (7) — assinatura anterior | 2026-11-19 | Consulte a Release histórica | `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b` |
| 5.0.1 (8) — novo certificado | 2026-11-20 | Será preenchido após a Release | `d60bd5c79718688e8988a27f6f4696df17d2ca0c2ea03613e9db85161decc7a6` |
