# ADR 0017 — Preço de mercado do SOL (fonte pública, sem chave, sem telemetria)

- **Status:** Aceito (exigência do proprietário, 2026-11-19)
- **Data:** 2026-11-19

## Contexto
O proprietário exige preço real, atualizado em < 10 s, sem valor inventado, com estado “desatualizado/indisponível” claro; saldos Devnet não têm valor, mas o preço real do SOL é exibido rotulado.

## Decisão
- Módulo novo `:data:price` (JVM puro, sem dependências; só `HttpsURLConnection`). Interface `PriceSource`; `PriceService` tenta em ordem **Coinbase** (`/v2/prices/SOL-{USD,BRL}/spot`) e, em falha, **Kraken** (`/0/public/Ticker?pair=SOLUSD`, só USD). Ambas públicas, sem chave (conferidas em G-PRICE-1).
- Requisição: só HTTPS (NSC sem cleartext), GET, sem cookies (nenhum `CookieHandler`), sem redirecionamento, `User-Agent: LockWallet` (o padrão do Android revelaria o modelo), timeouts de 4 s, corpo ≤ 16 KiB, JSON estrito (chave duplicada, lixo ao final, profundidade > 16 ⇒ rejeita), números como `BigDecimal` a partir do texto, faixa plausível (0,0001–10⁷), par conferido (`base=SOL`, `currency`).
- Polling a cada **6 s** apenas com a tela principal composta, app em primeiro plano e opção ligada; para em segundo plano e com o cofre bloqueado (a tela de bloqueio substitui a principal). Cotação com > 20 s ⇒ **DESATUALIZADO** com idade; sem nenhuma cotação ⇒ **indisponível** (“—”). Nunca há valor de fallback.
- Opção em Ajustes para desligar a consulta. O preço **nunca** entra em decisão de assinatura, análise de transação ou cálculo de taxa.

## Alternativas rejeitadas
CoinGecko sem chave (limite de taxa baixo para 6 s); Binance (bloqueios regionais); agregador próprio/servidor (proibido: sem backend); preço via RPC/oráculo on-chain (Devnet não reflete mercado).

## Consequências
Nova permissão `INTERNET` (reaproveitada pela RPC Devnet na Fase D). As fontes veem o IP do usuário e o horário das consultas.

## Risco residual
Fonte comprometida/MITM com CA válida pode mostrar preço falso (impacto só visual; sem pinning de certificado nesta fase). Privacidade: IP exposto a Coinbase/Kraken quando ligado.

## Teste
`PriceTest` (formatos reais das respostas, fallback, stale, rejeição de JSON malformado/implausível/duplicado, só HTTPS, intervalo < 10 s); `HomeUiTest` com fonte offline injetada (testes nunca acessam a rede).

## Condição para reabrir
Fonte encerrar o endpoint/limitar taxa, necessidade de mais moedas fiat, pinning de certificado.
