# Política de segurança

Lock Wallet é uma carteira Solana **autocustodial para a rede principal (Mainnet, dinheiro real)** — decisão do
mantenedor após revisão interna de segurança (ADR 0023; quatro rodadas de ataque, ver `docs/RELATORIO-SEGURANCA.md`).
Não houve auditoria externa independente, e os testes automáticos rodam em Android simulado: comece com valores
pequenos.

## Como relatar
- Relate vulnerabilidades **em privado** ao mantenedor (Christopher). Não abra issue pública com detalhes exploráveis.
- Inclua o SHA-256 do APK, aparelho/Android e passos para reproduzir.
- Escopo: app Android, `:core:*`, `:custody:*`, `:data:*`, ferramentas de build e scripts em `tools/`.

## Garantias do desenho (resumo)
- **Sem backend, sem telemetria, sem analytics.** O app só fala com RPCs públicos da Mainnet (ou um RPC HTTPS
  escolhido pelo usuário, conferido pelo genesis hash) e com a cotação pública do SOL (Coinbase/Kraken).
- **Sem assinatura às cegas.** Toda assinatura (carteira, Seed Vault, Lock Connect) passa pelo analisador/firewall;
  o hash exato revisado é o hash assinado. Programas desconhecidos são sinalizados; dados brutos só com aviso forte e
  confirmação explícita. Mensagens só são assinadas se forem texto apresentável que não seja uma transação.
- **Segredos nunca saem do aparelho** nem aparecem em logs ou estado de UI persistente. Nenhum `Log`, `println`,
  `printStackTrace` no código (verificado em CI; R8 remove `android.util.Log` de dependências no release).
- **Janela protegida:** `FLAG_SECURE`, `filterTouchesWhenObscured`, `HIDE_OVERLAY_WINDOWS`; `allowBackup=false`,
  regras de extração vazias, sem tráfego em claro.
- **Custódia local:** semente cifrada (Argon2id + AES-GCM) com chave do Android Keystore; PIN com limitador de
  tentativas que **nunca apaga** a carteira; alias do Keystore nunca recriado em silêncio.
- **Biometria (opcional, 3.0.0):** chave RSA no Keystore só com biometria forte por uso (CryptoObject), invalidada
  com nova digital; estado da política autenticado por HMAC Keystore; PIN após reboot (ou contador de boot
  indisponível), após erro e a cada 1/4/24/72 h (padrão 24 h; aumentar exige PIN); “Bloquear agora” força o PIN.
- **Seed Vault (Solana Mobile):** usado só quando o aparelho realmente o oferece; a semente nunca entra no app.
- **Lock Connect (MWA 2.x):** só associação local; sessões por dApp com revogação; pedidos de redes de teste ou
  desconhecidas são recusados; transações v0/multiassinante recusadas; taxa de prioridade acima de 0,005 SOL bloqueada.
- **Dinheiro real:** taxa de prioridade própria limitada a 0,001 SOL; envio para todos os RPCs; uma transação só é dada
  como expirada com evidência de dois RPCs independentes (evita pagamento duplo); `id` JSON-RPC conferido.
- **Interface:** folhas e avisos desenhados dentro da janela protegida (nunca diálogos/popups separados); com o app
  bloqueado nada além da tela de bloqueio é desenhado; colar a frase apaga a área de transferência.
- **Sinais de risco** (root, depurador, hooking como Frida/Xposed) são **informativos** na Central de Segurança;
  nada é apagado automaticamente. Esses sinais são heurísticos e contornáveis por um atacante com root.

## Cadeia de fornecimento e release
- Versões exatas em `gradle/libs.versions.toml`; hashes sha256 verificados (`gradle/verification-metadata.xml`).
- Cada release publica: SHA-256 do APK, SHA-256 do certificado de assinatura e SBOM CycloneDX
  (ver `docs/RELEASE-CHECKLIST.md`). A chave de assinatura é dedicada à Lock Wallet e fica fora do repositório.

## Limites conhecidos
Ver `docs/threat-model.md` (riscos residuais), `docs/RELATORIO-SEGURANCA.md` e `CHANGELOG.md`:
- Biometria (opcional, desligada por padrão; ADR 0024) permite desbloquear e **assinar** sem o PIN dentro da janela
  (mesmo boot, prazo escolhido — padrão 24 h, máximo 72 h —, sem erros de PIN, sem “Bloquear agora”). Coação biométrica nessa janela é um risco aceito; frase, exportação e
  criação de carteira sempre exigem o PIN, imposto na custódia.
- Sem auditoria externa e sem teste com fundos reais em aparelho por esta equipe; comece com valores pequenos.

## Verificação do release
- SHA-256 do certificado de assinatura (todas as versões): `3ce562ce3ac3bce9a87ab8745fb549ef8adfad692b280d13a22fdb7bd35d0b6b`.
- SHA-256 do APK final: ver `README.md` e `docs/RELEASE-CHECKLIST.md`.
