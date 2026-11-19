# Plano de escopo decidido

**Decisão:** você recebe **uma entrega única** com a carteira completa. Por dentro, eu construo em etapas, e uma etapa só começa quando a anterior passa em todos os testes. A **mainnet fica desligada** em todos os builds até uma auditoria externa. Essa é a única regra da especificação que não muda.

| Ordem | O que entra | Condição para seguir |
|---|---|---|
| A ✅ | Módulos, manifest seguro, ADRs, threat model, vetores BIP-39/SLIP-0010, contrato LWRP, máquina de estados | Feito: 43 testes verdes, os 3 endereços batem |
| B | Cofre local (envelope, PIN de 6 dígitos, biometria forte, Keystore/StrongBox), bloqueio de 1 h após 5 erros, LWRP cifrado | Testes de cripto, adulteração e falha |
| C | Criar carteira (12 ou 24 palavras, quiz) e restaurar (frase, LWRP, chave privada, JSON), Central de Segurança, várias carteiras | Restauração reproduz o mesmo endereço |
| D | Devnet SOL: saldo, receber (QR), enviar, histórico, taxas, Network Manager | Testes de rent, taxa, Unknown e expiração |
| E | Tokens SPL (Token-2022 bloqueado), ATA idempotente, ocultar token/token não verificado | Testes de mint/conta |
| F | Staking nativo completo (criar, delegar, desativar, sacar, split/merge), nunca líquido | Testes no Devnet |
| G | Seed Vault (só aparece se o aparelho tiver de verdade) | Teste em aparelho físico |
| H | Lock Connect (seed separada) + dApps via Mobile Wallet Adapter, com análise de cada transação | Nenhuma assinatura sem análise |
| I | SBOM, endurecimento e preparação para auditoria | Achados corrigidos |

Fica de fora: trading, DEX, compra com cartão, anúncios, rede social. O LOCK AIRGAP fica para depois da etapa I.
