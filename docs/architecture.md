# Arquitetura

```
:app ──► :custody:local ──► :core:crypto
  │            ├──────────► :core:solana
  │            └──────────► native/privacy-ring (Rust, JNI)
  ├──► :data:rpc ─────────► :core:solana
  ├──► :data:price (JVM, sem dependências; cotação pública HTTPS, ADR 0016)
  └──► :core:solana
```

`:app` não enxerga `:core:crypto` diretamente (guarda `verifyModuleBoundaries`).

| Módulo | Responsabilidade |
|---|---|
| `:core:crypto` (JVM) | BIP-39 (lista inglesa fixada por SHA-256), SLIP-0010, `SecretBytes`, Argon2id, AES-GCM, LWRP |
| `:core:solana` (JVM) | Base58, chaves públicas, codec de transações, analisador com allowlist, máquina de estados de transação |
| `:custody:local` (Android) | Envelope LWLE, chave no Keystore/StrongBox, PIN com espera crescente, biometria para visualização, journal, ponte do saldo confidencial |
| `:data:rpc` (JVM) | Coordenador de RPCs (genesis hash, vários servidores, expiração com evidência dupla) |
| `:data:price` (JVM) | Cotação do SOL (Coinbase → Kraken), estados fresco/desatualizado/indisponível |
| `native/privacy-ring` (Rust) | Operações do saldo confidencial (Default Ring), aprovação ligada aos bytes exatos |
| `:app` | Interface Compose, `AppController` (navegação e sessão), `ChainModel`, proteções de envio (`policy/SendPolicy`) |

## Peças do app

- **`policy/SendPolicy.kt`**: regras puras das proteções de envio (contatos, “Somente contatos”, limite diário em
  janela móvel de 24 h, confirmação de endereço novo, mudanças pendentes de 24 h). `ChainModel` aplica as regras na
  preparação, na autorização e antes da assinatura, com o horário da rede (`NetworkManager.networkTimeMs`).
- **`ui/Protections.kt`**: aba Privado (saldo confidencial), bloco de proteções de envio, limite diário e contatos.
- **`GifSanitizer.kt`** e **`ui/ProfileImage.kt`**: validação e limpeza do GIF, decodificação no tamanho de exibição
  (`ImageDecoder`), animação que pausa fora do primeiro plano ou com “Animações” desligado.
- **Bloqueio por inatividade**: `AppController.checkIdle`, chamado a cada 5 s com o app aberto e desbloqueado.
- **Variante de inspeção**: `BuildConfig.SCREENSHOT_CAPTURE_ALLOWED` só é `true` com
  `-Plockwallet.testBuild=true -Plockwallet.inspectionBuild=true` num build debug (pacote `com.lockwallet.app.test`).
  O release fixa `false`; `MainActivity.screenCaptureAllowed` exige as três condições.
- **`Motion`** (`ui/Theme.kt`): todas as animações passam por um único ponto e viram transição imediata quando o
  usuário desliga “Animações”.

## Persistência local

| Dado | Onde | Proteção |
|---|---|---|
| Semente / chave | envelope LWLE | Argon2id + AES-GCM + chave do Keystore |
| Preferências, contatos e proteções de envio | `SharedPreferences` privadas | armazenamento privado do app; backup do Android desligado |
| Fotografia de perfil | `files/` privado (`<hash>.webp` ou `<hash>.gif`) | armazenamento privado; removida com a carteira |

## Guardas de CI (`./gradlew ciCheck`)

Fronteiras de módulos, APIs proibidas (log, print, provider JCA, rede de teste, `Float`/`Double` em valores, Android
ou rede no core), testes JVM e Robolectric, `assembleDebug`, inspeção do manifest do APK release (aapt2) e lint
release. O workflow `.github/workflows/ci.yml` também roda os testes Rust. Nenhum workflow assina ou publica.
