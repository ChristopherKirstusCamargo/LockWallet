# Revisão de segurança da versão final

Data: 26/11/2026 · Escopo: código do `main`, APK release assinado e publicação no GitHub.

Revisão interna em dois papéis: **ataque** (tentar quebrar cada área abaixo, com a carteira tratada como se guardasse
valores altos) e **defesa** (corrigir, testar e documentar). Não é uma auditoria externa nem uma declaração de
conformidade; as áreas do OWASP MASVS serviram de roteiro.

## Achados e correções

| ID | Área | Achado | Severidade | Situação |
|---|---|---|---|---|
| F-01 | Saldo confidencial / RPC | A simulação da operação confidencial só conferia se havia erro. Um endpoint ou uma ponte adulterada podia preparar uma operação que retirasse do saldo público mais SOL do que o valor revisado. | Alta | 🔧 A simulação precisa devolver o saldo da carteira depois da operação. Se a queda passar de valor do depósito + taxa + margem de aluguel (0,01 SOL), a operação é recusada. Sem o saldo simulado, também é recusada. `CoordinatorTest`. |
| F-02 | Saldo confidencial / adulteração | A revisão mostrava o que a ponte nativa devolvia, sem confirmar que correspondia ao pedido do usuário. | Média | 🔧 A operação, o valor e o destino público devolvidos precisam ser iguais aos pedidos; na transferência, o destino digitado é obrigatório. Qualquer diferença descarta a mensagem. |
| F-03 | Armazenamento / tela | A URL do endpoint Helius, com a chave da API, aparecia em claro nos Ajustes. | Baixa | 🔧 Campo mascarado; a interface mostra só o host. |
| F-04 | Sobreposição (tapjacking) | `filterTouchesWhenObscured` descarta toques quando a janela está **totalmente** coberta; uma sobreposição **parcial** ainda podia passar toques. | Média | 🔧 Nas telas de PIN, bloqueio, revisão, saldo confidencial, frase e exportação, toques com `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` são descartados e o usuário é avisado. Soma-se a `HIDE_OVERLAY_WINDOWS`. |
| F-05 | Acessibilidade | Um serviço de acessibilidade malicioso pode ler a tela e simular toques. | Média | 🔧 No Android 14+, a janela é marcada como dado sensível (`ACCESSIBILITY_DATA_SENSITIVE_YES`): só serviços declarados como ferramentas de acessibilidade a enxergam. A Central de Segurança lista os serviços ativos que não se declaram ferramentas. |
| F-06 | Área de transferência | O endereço copiado ficava na área de transferência por tempo indeterminado. | Baixa | 🔧 Apagado depois de 2 min, se ainda for o mesmo. Colar a frase ou a chave continua apagando a área de transferência na hora. |
| F-07 | Envenenamento de endereço | A defesa dependia da leitura do endereço completo e do aviso de semelhança com destinos anteriores. | Média | 🔧 Destino novo exige digitar os 4 últimos caracteres antes de assinar (padrão ligado). O aviso de semelhança considera também os contatos. Opcional: “Somente contatos”. |
| F-08 | Sinais de root | Só caminhos clássicos de `su` e `test-keys`. | Baixa | 🔧 Também Magisk, KernelSU, APatch, `userdebug`/`eng` e montagens do Magisk. Continua informativo: root oculto passa despercebido. |
| F-09 | Cadeia de fornecimento | Componentes que deixaram de ser usados continuavam em `gradle/verification-metadata.xml`. | Baixa | 🔧 Removidos; a verificação de hashes cobre só o que entra no build. `cargo test --locked`. |
| F-10 | Relógio | Uma regra baseada em tempo (limite diário, esperas de 24 h) ficaria vulnerável a quem muda o relógio do aparelho. | Média | 🔧 As regras usam o horário do último bloco finalizado da rede, o menor entre dois servidores. Sem resposta, o envio é recusado. |
| F-11 | RPC / expiração | O servidor secundário padrão (PublicNode) devolve o **slot** em `getBlockHeight`. Quando o primário falhava, todo blockhash novo parecia expirado e o envio não andava. | Média | 🔧 A expiração usa o menor entre `getBlockHeight` e `getEpochInfo.blockHeight` (`blockHeightForExpiry`). Encontrado pelo teste real na Mainnet. `CoordinatorTest`. |
| F-12 | Limite diário | O registro guardava só os 200 envios mais recentes: 200 envios de poeira apagavam um envio grande e liberavam o limite. O código anterior falhou em 12 de 250 sementes do teste de propriedade. | Alta | 🔧 Ao passar de 200 registros, os mais antigos são somados num só (soma e horário mais recente), sem perder valor; somas com saturação. `SendLimitPropertyTest` (250). |
| F-13 | Proteções de envio / adulteração | Preferências adulteradas (valor inválido, tipo errado ou registro corrompido) podiam desligar o limite ou o “Somente contatos”, ou travar o app. | Média | 🔧 Falha fechada: limite inválido vira 1 lamport; chave presente mantém “Somente contatos”; registro corrompido conta como janela cheia por 24 h; tipo errado não trava. `SendPolicyTamperTest` (96). |
| F-14 | Envenenamento de endereço | O aviso de semelhança exigia 4+4 caracteres iguais e diferenciava maiúsculas: endereços com 3+3 iguais ou só com maiúsculas trocadas passavam. | Média | 🔧 Semelhante se início e fim têm ≥ 3 iguais, ou início ou fim têm ≥ 5, sem diferenciar maiúsculas. `PoisonedAddressIsFlaggedTest` (250), `UnrelatedAddressIsNotFlaggedTest` (150), `AllowlistLookalikeTest` (120). |
| F-15 | RPC malicioso | Um blockhash malformado em `getLatestBlockhash` escapava como `IllegalArgumentException` em vez de erro de rede tratado. | Baixa | 🔧 Vira `RpcException` (resposta inválida). `HostileRpcResultTest` (720), `HostileRpcEnvelopeTest` (360). |
| F-16 | PIN / adulteração | O contador de tentativas tem duas cópias. Restaurar uma cópia antiga ao lado de outra apagada ou corrompida zerava o contador. | Média | 🔧 Com o cofre presente, uma cópia ausente ou inválida bloqueia por 24 h (estado gravado). `PinStateTamperTest` (183), `PinBruteForcePropertyTest` (200). Substituir as **duas** cópias por versões antigas exige root (limite abaixo). |
| F-17 | RPC / disponibilidade | O servidor secundário padrão recusa `getMultipleAccounts` com mais de 10 contas. Com o primário limitado (HTTP 429), a lista de stakes e os metadados de tokens falhavam. | Baixa | 🔧 Lotes de 10 contas; um primário com 429 recebe até três novas tentativas (0,9 s, 1,8 s, 3,6 s) quando o secundário não responde. Encontrado pelo teste real na Mainnet. `FallbackBatchTest` (100), `NetworkTest`. |

## Áreas conferidas sem achado novo

| Área | O que foi conferido |
|---|---|
| RPC malicioso | Genesis hash fixado, TLS só com CAs do sistema, `id` JSON-RPC conferido, envio a vários servidores, expiração só com evidência de dois servidores, decodificação local. |
| Adulteração de transação | O hash revisado é o hash assinado; o assinante confere fee payer, allowlist e simulação obrigatória. |
| Intents e deep links | Só a Activity principal é exportada; ela não lê dados nem extras. Receiver do profileinstaller removido. `verify_apk_manifest.py` e `ManifestAndWindowSecurityTest`. |
| Backup e restauração | `allowBackup=false`, `fullBackupContent=false` e regras de extração vazias (nuvem e transferência entre aparelhos). |
| Memória | PIN em `CharArray` apagado depois do uso; sementes em `SecretBytes` zeradas (best-effort, ADR 0012). |
| Tempo de resposta | PIN conferido por KDF + AEAD; comparações de MAC em tempo constante (`MessageDigest.isEqual`). |
| Autenticação | PIN obrigatório em toda assinatura, aplicado pela camada de custódia; biometria só abre a visualização. |

## Proteções opcionais novas

Em **Ajustes › Segurança**:

- **Contatos** (até 200) e **Somente contatos**: SOL e tokens só para contatos ou carteiras suas; um contato novo
  só recebe 24 h depois de adicionado.
- **Limite diário de SOL:** janela móvel de 24 h para envios de SOL, transferências confidenciais e retiradas para
  terceiros. Stake não conta. Tokens são cobertos por “Somente contatos”.
- **Confirmar endereço novo** (padrão ligado).
- Endurecer vale na hora. Afrouxar (desligar “Somente contatos”, aumentar ou remover o limite, desligar a confirmação)
  exige o PIN e só vale 24 h depois; pode ser cancelado.
- As regras são aplicadas na preparação, na autorização e imediatamente antes da assinatura.

Avaliado e não incluído: **atraso antes de envios grandes.** Uma transação Solana assinada expira em cerca de 60 a
90 s, então o atraso teria de acontecer antes da assinatura; contra alguém que já sabe o PIN ele protege pouco além do
que o limite diário e a espera de 24 h para afrouxar já oferecem. Também não há “botão de pânico”: um bloqueio falso
daria uma sensação de segurança que o app não pode garantir.

## Limites que continuam

- **Sem auditoria externa independente.** Testes automáticos rodam em Android simulado (Robolectric); não houve teste
  em aparelho físico com fundos reais neste ambiente.
- As proteções de envio ficam no armazenamento privado do app. Quem tem root pode alterá-las; elas protegem contra
  quem tem o aparelho desbloqueado e o PIN, não contra um sistema comprometido.
- Se o registro de envios do limite diário for corrompido, o app trata a janela como cheia por 24 h (falha fechada).
- O limite diário conta SOL; tokens são cobertos por “Somente contatos”.
- O servidor secundário padrão recusa a lista de contas de token por dono; com o primário limitado por muito tempo,
  a lista de tokens fica indisponível até o primário responder.
- Um RPC personalizado poderia informar um horário falso; o app usa o menor horário entre ele e o servidor secundário.
- A marcação para acessibilidade vale no Android 14+; serviços declarados como ferramentas de acessibilidade
  continuam enxergando a tela.
- Textos digitados em campos (por exemplo, a frase na restauração) passam por `String` do Compose e não podem ser
  apagados da memória de forma garantida.
- Root, sistema comprometido, coação biométrica dentro do prazo escolhido e RPCs em conluio continuam como descritos
  em `docs/threat-model.md` e `docs/RELATORIO-SEGURANCA.md`.

## Revisão anterior (mantida)

- Workflows que geravam chaves no CI e publicavam Releases foram removidos; fica um único workflow de CI sem
  assinatura, sem Secrets e sem publicação.
- Variante de inspeção (captura de tela liberada) só em build debug com pacote `com.lockwallet.app.test`; o release
  fixa `FLAG_SECURE` (`ReleaseScreenPolicyTest`).
- Fotografia GIF limpa e limitada (`GifSanitizer`: ≤ 4 MB, ≤ 1024 px, ≤ 300 quadros; `GifProfilePhotoTest`).
- Bloqueio por inatividade (1/2/5/10 min, padrão 5); excluir a carteira remove a fotografia.

## Assinatura e repositório

- O APK é assinado com a chave de produção mantida offline (certificado
  `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b`). A chave não está no GitHub, no CI nem no Git.
- O repositório foi publicado como um único commit com os arquivos finais. Nenhuma chave privada, keystore,
  `signing.properties`, `local.properties` ou token está no conteúdo versionado (ver `docs/RELEASE-CHECKLIST.md`).

## Testes

- `./gradlew ciCheck`: 5.590 testes JVM/Robolectric (0 falhas, 0 erros, 0 ignorados), lint release (0 erros),
  guardas de arquitetura e inspeção do manifest do APK release. Por módulo: app 2.767, custody/local 465, core/solana 833, core/crypto 215, data/rpc 1.302, data/price 8.
- `cargo test --locked --manifest-path native/privacy-ring/Cargo.toml --lib`: 4 testes.
- `./gradlew :data:rpc:mainnetTest`: simulação real na Mainnet das mensagens de envio e stake, sem assinar nem enviar
  (fora da suíte hermética por depender da rede). Passou em 26/11/2026.

Grupos novos desta revisão (casos executados):

| Grupo | Suítes | Casos |
|---|---|---|
| Segurança · proteções de envio (app) | SendLimitPropertyTest 250, SendCooldownBypassTest 72, SendPolicyTamperTest 96, AllowlistLookalikeTest 120, PoisonedAddressIsFlaggedTest 250, UnrelatedAddressIsNotFlaggedTest 150 | 938 |
| Segurança · entradas hostis (app) | UserAmountAbuseTest 30, DisplayedAmountRoundTripTest 150, SecretPasteTest 24, HostileIntentTest 28 | 232 |
| Segurança · PIN (custódia) | PinBruteForcePropertyTest 200, PinStateTamperTest 183 | 383 |
| Segurança · transações e formatos (core/solana) | DecodeDivergenceTest 300, AnalyzerRefusalTest 12, Base58PropertyTest 200, AmountParseTest 41, AmountRoundTripTest 200, QrPayloadTest 29 | 782 |
| Segurança · backup (core/crypto) | BackupTamperTest 133, BackupShapeTest 24, BackupPasswordNearMissTest 16 | 173 |
| Segurança · RPC hostil (data/rpc) | HostileRpcResultTest 720, HostileRpcEnvelopeTest 360, ConfidentialGuardTest 84, FallbackBatchTest 100 | 1.264 |
| Design · valores enormes e casas decimais | CompactBoundaryTest 40, CompactPropertyTest 400, CompactFiatTest 120, CompactUnitsTest 112, CompactEdgeTest 4, DisplayDecimalsTest 200, DisplayDecimalsEdgeTest 7 | 883 |
| Design · layout (4 larguras × 4 escalas de fonte × 2 temas) | HeroBalanceLayoutTest 160, AssetRowLayoutTest 96, SettingsRowLayoutTest 32, ReviewAmountLayoutTest 96 | 384 |
| Design · contraste do vidro | ContrastMatrixTest 168, MonochromeTest 24 | 192 |
| **Total** | segurança 3.772 · design 1.459 | **5.231** |
