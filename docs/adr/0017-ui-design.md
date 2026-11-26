# ADR 0017 — Sistema de interface (Fase C+)

- **Status:** Aceito (exigências do proprietário, 2026-11-19)
- **Data:** 2026-11-19

## Decisão
- Tela principal única (inspiração Phantom/Uniswap): contador centralizado no topo com transição animada por dígito (spring), pill flutuante inferior (Início, Stake, Privado, Ajustes) com indicador animado por spring; ações Enviar, Receber, Stake e Ler QR em botões redondos centralizados logo abaixo do saldo. Fluxos sensíveis (criação, restauração, PIN, frase, exportação) são telas cheias sobre a principal, com transições animadas.
- Ajustes: a tela principal é só o cartão do perfil e uma lista de categorias (Segurança, Moeda, Privacidade, Carteiras, Aparência, Sobre), com ícone, título e seta, no estilo de listas agrupadas do iOS. Cada categoria abre uma tela própria e cada opção existe em um só lugar.
- Superfícies: um único material de vidro (`Modifier.glass`) para cartões, folhas, diálogos e barra; desfoque real do fundo com RenderEffect, reflexo especular, borda interna e profundidade; contraste verificado por `ContrastMatrixTest`.
- Exatamente dois temas monocromáticos: branco-sobre-preto e preto-sobre-branco (preferência não secreta em SharedPreferences). Status comunicado por símbolo + texto (✓ ! ✕ – i), não por cor.
- “Black glass”: superfícies translúcidas com borda fina sobre um brilho ambiente monocromático desfocado por `RenderEffect` (`Modifier.blur`, API 31 = minSdk). Desfoque do conteúdo *atrás* de cada superfície não é usado (custo/compatibilidade); o fallback é a translucidez.
- Enquanto não houver RPC (Fase D) o contador mostra o preço real do SOL, rotulado; nunca um saldo inventado. “Enviar” e Stake explicam a fase em que chegam (sem botões mortos).
- Segurança mantida: FLAG_SECURE, `filterTouchesWhenObscured`, `setHideOverlayWindows`, cofre bloqueado e segredos descartados em `onStop` (exceto com o seletor de arquivos do sistema à frente), palavras desenhadas direto de `CharArray` no Canvas (sem `String`), PIN em `CharArray` próprio, trabalho pesado fora da main thread (Argon2id, derivação, rede).

## Risco residual
`TextField` do Compose exige `String` para frase/chave/senha digitadas (limpo logo após o uso; ADR 0012). QR sempre preto-sobre-branco por compatibilidade com leitores.
