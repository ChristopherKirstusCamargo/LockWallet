# ADR 0018 — Sistema de interface (Fase C+)

- **Status:** Aceito (exigências do proprietário, 2026-11-19)
- **Data:** 2026-11-19

## Decisão
- Tela principal única (inspiração Phantom/Uniswap): contador centralizado no topo com transição animada por dígito (spring), pill flutuante inferior (Início, Stake, dApps, Ajustes) com indicador animado por spring, botão “+” acima da pill à direita abrindo ações (Receber, Enviar, Criar, Restaurar). Fluxos sensíveis (criação, restauração, PIN, frase, exportação) são telas cheias sobre a principal, com transições animadas.
- Exatamente dois temas monocromáticos: branco-sobre-preto e preto-sobre-branco (preferência não secreta em SharedPreferences). Status comunicado por símbolo + texto (✓ ! ✕ – i), não por cor.
- “Black glass”: superfícies translúcidas com borda fina sobre um brilho ambiente monocromático desfocado por `RenderEffect` (`Modifier.blur`, API 31 = minSdk). Desfoque do conteúdo *atrás* de cada superfície não é usado (custo/compatibilidade); o fallback é a translucidez.
- Enquanto não houver RPC (Fase D) o contador mostra o preço real do SOL, rotulado; nunca um saldo inventado. “Enviar”, Stake e dApps explicam a fase em que chegam (sem botões mortos).
- Segurança mantida: FLAG_SECURE, `filterTouchesWhenObscured`, `setHideOverlayWindows`, cofre bloqueado e segredos descartados em `onStop` (exceto com o seletor de arquivos do sistema à frente), palavras desenhadas direto de `CharArray` no Canvas (sem `String`), PIN em `CharArray` próprio, trabalho pesado fora da main thread (Argon2id, derivação, rede).

## Risco residual
`TextField` do Compose exige `String` para frase/chave/senha digitadas (limpo logo após o uso; ADR 0013). QR sempre preto-sobre-branco por compatibilidade com leitores.
