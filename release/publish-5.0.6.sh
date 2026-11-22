#!/usr/bin/env bash
# One-shot publisher for Lock Wallet 5.0.6.
# Invoked by .github/workflows/release-5.0.6.yml (added by hand on main).
# Downloads the 5.0.6 source patch and the signed APK from a relay whose
# URLs live in release/relay-urls-5.0.6.txt (read from the current main,
# so they can be refreshed without touching this script); both downloads
# are verified against pinned SHA-256 hashes before use. Commits the
# source changes to main as the repo owner, publishes GitHub release
# v5.0.6 with notes in the 5.0.5 format, and deletes the stray
# probe-write-perm branch. The signing key stays outside the tree.
set -euo pipefail
cd "$GITHUB_WORKSPACE"

APK_SHA256="7ec0a45eed50c7a9317e07d11ac8a7b2ad1cd61be003669530ca6725cfa9ed15"
PATCH_SHA256="588ee84f60e70693eb32ecb9f4d5f7820e0bea84898ee42a79602133a379646b"
PUSH_URL="https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"

relay_view() {
  gh api "repos/${GITHUB_REPOSITORY}/contents/release/relay-urls-5.0.6.txt" \
    --jq '.content' | tr -d '\n' | base64 -d
}

dl_link() {
  local view="$1" name="$2" link=""
  for i in 1 2 3; do
    link="$(curl -fsSL --retry 2 --max-time 60 "$view" | grep -oE "https://tmpfiles\.org/dl/[^\"]+/${name}" | head -n 1)"
    [ -n "$link" ] && { echo "$link"; return 0; }
    sleep 3
  done
  echo "Could not extract direct link for $name from $view" >&2
  return 1
}

echo "::group::Fetch current main"
git fetch origin main
echo "::endgroup::"

SRC_SHA=""
if git log --format='%s' origin/main -n 12 | grep -q '^Release 5\.0\.6: dual-audit security fixes'; then
  echo "Source commit is already on main; locating it."
  SRC_SHA="$(git log --format='%H %s' origin/main -n 12 | awk '/Release 5\.0\.6: dual-audit security fixes/ {print $1; exit}')"
else
  echo "::group::Commit 5.0.6 source changes to main"
  RELAY="$(relay_view)"
  PATCH_VIEW="$(grep -E '^PATCH=' <<<"$RELAY" | head -n 1 | cut -d= -f2- | tr -d '[:space:]')"
  case "$PATCH_VIEW" in https://tmpfiles.org/*) ;; *) echo "Unexpected patch relay URL" >&2; exit 1;; esac
  echo "Patch relay page: $PATCH_VIEW"
  PLINK="$(dl_link "$PATCH_VIEW" rel506.patch)"
  echo "Patch direct link: $PLINK"
  curl -fsSL --retry 3 --max-time 120 -o "$RUNNER_TEMP/rel506.patch" "$PLINK"
  echo "${PATCH_SHA256}  $RUNNER_TEMP/rel506.patch" | sha256sum -c -
  git apply --check "$RUNNER_TEMP/rel506.patch"
  git apply "$RUNNER_TEMP/rel506.patch"
  cat > "$RUNNER_TEMP/commitmsg.txt" <<'MSG'
Release 5.0.6: dual-audit security fixes (versionCode 14)

- Restore screens (recovery phrase, private key, Recovery Package
  password) warn when the active keyboard is not the device
  manufacturer's IME: a third-party keyboard receives every
  keystroke, including secrets. The warning points to the stock
  keyboard or the Paste button; it does not block the flow.
- Default Ring (audit LW-03/CSV-LW-06): the confidential transfer
  fee is capped and displayed from a byte-exact local bound over
  the V1 message (base fee plus header priority fee, V1MessageFee,
  fail-closed on any layout deviation), never from the RPC answer
  alone.
- Default Ring review shows the recipient Shielded address exactly
  as typed, alongside the derived public address, so the
  destination can be checked against what was pasted.
- Lock Connect: the Token-2022 default-deny extension policy now
  applies to dApp transactions too, matching the native send flow
  (parity; allowed tokens remain identical).
- Lock Connect: connect/sessions.json is now stored in an
  HMAC-SHA256 authenticated envelope keyed by a non-exportable
  Keystore key (same pattern as the transaction journal). Missing,
  corrupted, tampered or legacy files reset to an empty list and
  are rewritten authenticated (fail-closed; dApps reauthorize).
- Supply chain: native builds (Gradle and CI) require cargo
  --locked with the versioned Cargo.lock; dependencies are never
  re-resolved.
- CI: repair the corrupted push trigger of privacy-rings-android.yml.
- versionCode 14, versionName Final; CHANGELOG, README and release
  checklist updated with the 5.0.6 signing certificate fingerprint.

The signing key stays outside the tree.
MSG
  git rm -q .github/workflows/release-5.0.6.yml release/publish-5.0.6.sh
  git add -A
  git -c user.name="Christopher Kirstus Camargo" -c user.email="cristofercamargo339@gmail.com" commit -q -F "$RUNNER_TEMP/commitmsg.txt"
  git push "$PUSH_URL" HEAD:main
  SRC_SHA="$(git rev-parse HEAD)"
  echo "Pushed source commit $SRC_SHA"
  echo "::endgroup::"
fi
test -n "$SRC_SHA"
echo "Release target commit: $SRC_SHA"

echo "::group::Download signed APK and verify SHA-256"
RELAY="$(relay_view)"
APK_VIEW="$(grep -E '^APK=' <<<"$RELAY" | head -n 1 | cut -d= -f2- | tr -d '[:space:]')"
case "$APK_VIEW" in https://tmpfiles.org/*) ;; *) echo "Unexpected APK relay URL" >&2; exit 1;; esac
echo "APK relay page: $APK_VIEW"
ALINK="$(dl_link "$APK_VIEW" app-release.apk)"
echo "APK direct link: $ALINK"
curl -fsSL --retry 3 --max-time 300 -o "$RUNNER_TEMP/lockwallet.apk" "$ALINK"
echo "${APK_SHA256}  $RUNNER_TEMP/lockwallet.apk" | sha256sum -c -
cp release/lockwallet.apk.sha256 "$RUNNER_TEMP/lockwallet.apk.sha256"
echo "::endgroup::"

echo "::group::Publish GitHub release v5.0.6"
cat > "$RUNNER_TEMP/notes.md" <<'NOTES'
# Lock Wallet 5.0.6

Android release build.

- Android versionCode: 14
- Android versionName: Final
- APK SHA-256: `7ec0a45eed50c7a9317e07d11ac8a7b2ad1cd61be003669530ca6725cfa9ed15`
- Signing certificate SHA-256: `013f43c64fb14b2df1e2dab33107d249b8a19300a80df9b114ce10fb76a97def`

Publicação do `main` com as correções de segurança da auditoria dupla da 5.0.5.

- **IME na restauração:** as telas de digitação da frase de recuperação, da chave privada e da senha do Recovery Package agora avisam quando o teclado ativo não é o do fabricante do aparelho — um teclado de terceiros recebe cada tecla digitada, inclusive segredos. O aviso orienta trocar para o teclado original ou usar o botão Colar; não bloqueia o fluxo.
- **Default Ring (taxa local):** a taxa exibida e limitada na transferência confidencial passa a considerar o teto calculado localmente a partir dos bytes da própria mensagem V1 (custo base + prioridade do cabeçalho), nunca apenas a resposta do RPC (`V1MessageFee`, fail-closed). Um RPC desonesto não consegue subnotificar o custo.
- **Default Ring (revisão):** a revisão da transferência confidencial exibe o endereço Shielded do destinatário exatamente como foi digitado, além do endereço público derivado — a única forma de conferir o destino contra o que colou.
- **Lock Connect (Token-2022):** a política default-deny de extensões Token-2022, já aplicada no envio nativo, passa a valer também nas transações de dApps: mint fora da allowlist, extensão perigosa ou dados de mint indisponíveis bloqueiam a assinatura (paridade entre os dois fluxos; tokens permitidos continuam idênticos).
- **Lock Connect (integridade do registro):** o registro de sessões dApps (`connect/sessions.json`) passa a ser gravado em envelope autenticado com HMAC-SHA256 de chave não exportável do Keystore (o mesmo padrão do diário de transações). Arquivo ausente, corrompido, adulterado ou no formato antigo sem autenticação vira lista vazia e regravação autenticada (fail-closed; dApps reautorizam, sessões duram 15 min).
- **Cadeia de suprimentos Rust:** builds nativos (Gradle e CI) passam a exigir `--locked`, complementando o `Cargo.lock` versionado e os pins exatos do Cargo.toml; o build nunca re-resolve dependências.
- **CI:** corrigido o gatilho de push do workflow de integração Privacy Rings (o filtro de branch estava corrompido, o que impedia o teste da integração nativa em commits diretos na main).
- **Avaliados e deliberadamente não alterados nesta versão:** pinning TLS de RPC (o gate atual permanece: genesis verificada, análise local dos bytes e dupla simulação) e a migração automática de cofres legados `PIN_ONLY` (o aviso persistente no início e na Central de Segurança permanece).

**Este não é o certificado da 5.0.5** (`c98f56ccc38e13998833346921dd2cf4df61b3a41d9f83c16c11e16df30c3102`) **nem o da 5.0.4** (`75ac67c304d00f512c60d7fd1d275dacfc743964d2ab6275ff6a4eb8688086e3`) **nem o da 5.0.3** (`dcc94672cea0c306557bfee7e9b832cc935709c5f6dc104e75f5d316edcb1507`) **nem o certificado aposentado da 5.0.1/5.0.2** (`ed4c0025c0b4d551fa95945c89d3d24e504349a2fab289ff90de66409fff8c13`). O Android não instala este APK por cima dessas versões. Antes de atualizar, guarde um backup verificado. Nunca compartilhe a frase de recuperação nem o Recovery Package.

Nenhuma chave privada, keystore ou senha de assinatura entra nesta Release ou no repositório.
NOTES
if gh release view v5.0.6 -R "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  echo "Release v5.0.6 already exists; skipping creation."
else
  gh release create v5.0.6 -R "$GITHUB_REPOSITORY" \
    --target "$SRC_SHA" \
    --title "Lock Wallet 5.0.6" \
    --notes-file "$RUNNER_TEMP/notes.md" \
    "$RUNNER_TEMP/lockwallet.apk" \
    "$RUNNER_TEMP/lockwallet.apk.sha256"
fi
gh release view v5.0.6 -R "$GITHUB_REPOSITORY" --json name,tagName,assets --jq '{name, tagName, assets: [.assets[] | {name, size}]}'
echo "::endgroup::"

echo "::group::Delete stray probe-write-perm branch"
git push "$PUSH_URL" --delete probe-write-perm || echo "probe-write-perm already removed"
echo "::endgroup::"

echo "Release 5.0.6 published."
