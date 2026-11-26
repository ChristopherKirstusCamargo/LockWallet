# Política de segurança

Lock Wallet é uma carteira Solana de autocustódia para a rede principal (Mainnet, dinheiro real). A versão publicada
como **Lock Wallet** é a versão final; não há novas versões planejadas. Todas as revisões de segurança foram internas
(`docs/RELATORIO-SEGURANCA.md` e `docs/REVISAO-SEGURANCA.md`). **Não houve auditoria externa independente**, e os
testes automáticos rodam em Android simulado. Comece com valores pequenos.

## Como relatar
- Relate vulnerabilidades **em privado**, pelo recurso “Report a vulnerability” (aba *Security*) do repositório no
  GitHub. Não abra issue pública com detalhes exploráveis.
- Inclua o SHA-256 do APK, aparelho/Android e passos para reproduzir.
- Escopo: app Android, `:core:*`, `:custody:*`, `:data:*`, `native/privacy-ring`, ferramentas de build e scripts em `tools/`.
- Não há prazo de resposta garantido. A chave de assinatura fica guardada offline e só seria usada numa correção
  extraordinária de uma falha grave.

## Versões com suporte

Só a Release **Lock Wallet** publicada neste repositório tem suporte, assinada com a chave de produção (certificado
abaixo). Instalações assinadas com esse mesmo certificado atualizam no lugar. Instalações assinadas com outro
certificado não atualizam: confira o backup (frase ou Recovery Package), desinstale, instale o APK oficial e restaure.

## Garantias do desenho (resumo)
- **Sem backend, sem telemetria, sem analytics.** O app só fala com RPCs públicos da Mainnet (ou um RPC HTTPS
  escolhido pelo usuário, conferido pelo genesis hash) e com a cotação pública do SOL (Coinbase/Kraken).
- **Sem assinatura às cegas.** Envio e stake passam pelo analisador com allowlist fechada; programas fora dela são
  recusados. As operações confidenciais têm validação própria dos bytes (ADR 0023). Em todos os casos, o hash
  revisado é o hash assinado e a simulação é obrigatória antes de assinar.
- **Segredos nunca saem do aparelho** nem aparecem em logs ou estado de UI persistente. Nenhum `Log`, `println`,
  `printStackTrace` no código (verificado em CI; R8 remove `android.util.Log` de dependências no release).
- **Janela protegida:** `FLAG_SECURE`, `filterTouchesWhenObscured`, `HIDE_OVERLAY_WINDOWS`; toques sob sobreposição
  parcial descartados nas telas sensíveis; janela marcada como dado sensível para acessibilidade (Android 14+);
  `allowBackup=false`, regras de extração vazias, sem tráfego em claro.
- **Custódia local:** semente cifrada (Argon2id + AES-GCM) com chave do Android Keystore; PIN com limitador de
  tentativas que **nunca apaga** a carteira; alias do Keystore nunca recriado em silêncio.
- **Biometria (opcional):** chave RSA no Keystore só com biometria forte por uso (CryptoObject), invalidada
  com nova digital; estado da política autenticado por HMAC Keystore; PIN após reboot (ou contador de boot
  indisponível), após erro e a cada 1/4/24/72 h (padrão 24 h; aumentar exige PIN); “Bloquear agora” força o PIN.
  A biometria só autoriza desbloqueio de visualização; toda assinatura é PIN-only e essa regra também é aplicada
  diretamente pelos métodos de assinatura da camada de custódia.
- **Proteções de envio (opcionais):** contatos, “Somente contatos”, limite diário de SOL e confirmação de endereço
  novo (padrão ligado). Endurecer vale na hora; afrouxar exige PIN e espera 24 h. As regras usam o horário da rede
  (o menor entre dois servidores) e falham fechadas sem ele.
- **Saldo confidencial:** endpoint Helius dedicado e conferido; simulação confere a variação do saldo público; o
  resultado da ponte nativa precisa corresponder ao pedido (ADR 0023).
- **Dinheiro real:** taxa de prioridade própria limitada a 0,001 SOL; acima de 0,005 SOL o analisador bloqueia; envio para todos os RPCs; uma transação só é dada
  como expirada com evidência de dois RPCs independentes (evita pagamento duplo); `id` JSON-RPC conferido.
- **Interface:** folhas e avisos desenhados dentro da janela protegida (nunca diálogos/popups separados); com o app
  bloqueado nada além da tela de bloqueio é desenhado; colar a frase apaga a área de transferência; o endereço copiado
  sai dela após 2 min.
- **StrongBox:** se a chave ativa do cofre estiver confirmada como StrongBox e o patch Android for anterior a
  05/04/2026, a Central de Segurança marca o patch como **Risco** por causa da CVE-2025-48651. A correção exige
  atualizar o Android; o app não desativa nem migra silenciosamente uma chave existente.
- **Captura de tela:** o release sempre ativa `FLAG_SECURE`. A única exceção é a variante de inspeção, um build
  debug com pacote próprio (`com.lockwallet.app.test`) e duas opções de build explícitas; um teste fixa essa regra.
- **Sessão:** bloqueio ao sair do primeiro plano, ao apagar a tela e após inatividade (1/2/5/10 min, padrão 5).
- **Dados locais:** preferências, contatos, proteções de envio e fotografia (JPEG/PNG/WebP/HEIF ou GIF limpo e
  limitado) ficam no armazenamento privado do app, sem backup do Android. A fotografia é apagada com a carteira.
- **Sinais de risco** (root, incluindo Magisk/KernelSU/APatch, depurador, hooking como Frida/Xposed e serviços de
  acessibilidade que não são ferramentas de acessibilidade) são **informativos** na Central de Segurança;
  nada é apagado automaticamente. Esses sinais são heurísticos e contornáveis por um atacante com root.

## Cadeia de fornecimento e release
- Versões exatas em `gradle/libs.versions.toml`; hashes SHA-256 verificados (`gradle/verification-metadata.xml`).
- O único workflow (`.github/workflows/ci.yml`) roda testes, lint e a inspeção do manifest. Não assina, não usa
  Secrets e não publica.
- O APK oficial é assinado fora do CI, com a chave de produção mantida offline. Nenhuma chave, keystore (cifrado ou
  não) ou senha fica no Git, em artefatos de CI ou em Secrets do GitHub.
- O repositório é publicado como um único commit com os arquivos finais; o histórico anterior não faz parte dele.

## Limites conhecidos
Ver `docs/threat-model.md`, `docs/RELATORIO-SEGURANCA.md` e `docs/REVISAO-SEGURANCA.md`:
- Sem auditoria externa e sem teste com fundos reais em aparelho físico neste ambiente.
- Aparelho com root ou sistema comprometido pode ler a tela e controlar o app; sinais de root/hook são informativos.
- Biometria (opcional, desligada por padrão) só abre a visualização dentro do prazo escolhido (padrão 24 h, máximo
  72 h). Coação nessa janela pode expor saldos, mas não autoriza assinaturas: a camada de custódia exige PIN.
- Servidores RPC em conluio podem mostrar saldo ou simulação errados; não podem assinar nem mover fundos.
- As proteções de envio ficam no armazenamento privado do app; quem tem root pode alterá-las.
- Contatos ficam em texto claro no armazenamento privado; não contêm segredos, mas revelam endereços.

## Verificação do release
- Certificado de assinatura (SHA-256):
  `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b`.
- SHA-256 do APK: publicado na Release “Lock Wallet” (arquivo `LockWallet.apk.sha256`) e no README.
