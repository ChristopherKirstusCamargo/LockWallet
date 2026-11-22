# ADR 0026 — Solana Privacy Rings (Helius)

Status: proposta em implementação; operações privadas desabilitadas até cumprir os gates abaixo.

Data: 2026-11-22.

## Contexto

A LockWallet é uma carteira Android de autocustódia, escrita em Kotlin/Jetpack Compose, com custódia local cifrada e suporte opcional ao Solana Mobile Seed Vault. O aplicativo não possui backend próprio e o analisador de transações usa uma política fechada: uma instrução desconhecida não pode ser assinada apenas porque veio de um dApp ou de um SDK.

A integração pretendida é o anel confidencial permissionless da Solana Privacy Rings. No anel padrão, o ativo e a quantidade de uma transferência privada são criptografados; os endereços do remetente e do destinatário continuam públicos. Depósitos de um saldo público para o saldo privado revelam origem, ativo e quantidade. Retiradas para o saldo público também revelam ativo, quantidade e destino. Custom Rings anônimos não fazem parte desta integração inicial.

A documentação oficial da Helius consultada em 2026-11-22 publica SDKs Rust e TypeScript. Não foi identificado um SDK Android/Kotlin oficial. O cliente Rust publicado pode ser uma base, mas precisa ser integrado e validado para Android; os exemplos públicos não devem ser copiados como código de produção sem revisão.

## Estado atual do LockWallet

- `:data:rpc` possui Network Manager, RPC primário configurável e um RPC secundário mantido pelo aplicativo. Ambos são conferidos pelo Genesis Hash da Mainnet.
- A tela Ajustes → Servidor da rede já aceita uma URL RPC personalizada. O valor é persistido nas preferências locais.
- A Helius documenta um endpoint que atende RPC Solana, indexador Photon e provador. As chamadas de privacidade usam rotas específicas sob `/v1/spr/{method}`; não são chamadas JSON-RPC convencionais para simplesmente encaminhar ao RPC secundário.
- O módulo de custódia local não entrega a seed ou a chave privada à interface. O Seed Vault assina por meio do provedor do sistema e não exporta a semente ao processo da carteira.
- O Transaction Security Analyzer trabalha com uma allowlist fechada. O programa de privacidade e suas instruções ainda não foram integrados ao parser e à revisão humana da transação.

## Decisões de segurança

1. **A UI não pode simular privacidade.** Enquanto os fluxos criptográficos não estiverem completos, o envio normal deve continuar identificado como público. A tela de Ajustes pode explicar o protocolo, mas não pode criar saldos fictícios nem permitir uma assinatura privada.
2. **Não ampliar a allowlist de forma genérica.** As instruções do protocolo precisam de um analisador tipado, com decodificação de contas, valores públicos, destinatários, taxas, instruções internas e efeitos esperados. Uma exceção ampla para o programa da Helius não é aceitável.
3. **A custódia não pode ser contornada.** O SDK não pode receber a frase de recuperação pela UI, gravar chaves em SharedPreferences, arquivos comuns, logs ou telemetria. A integração precisa definir como as chaves viewing e nullifier serão criadas, protegidas, restauradas e eliminadas dentro de uma fronteira de custódia.
4. **Compatibilidade de custódia deve ser explícita.** O cliente Rust de referência cria o ShieldedKeypair a partir de um keypair Ed25519. A carteira local e o Seed Vault têm contratos diferentes. Não se deve exportar uma seed do Seed Vault nem degradar silenciosamente para uma carteira local. Se um provedor não puder cumprir a política de chaves, a função privada deve permanecer indisponível para aquela carteira.
5. **O viewing key não deve ser delegado por padrão.** A leitura do saldo deve preferir descriptografia local. Compartilhar uma viewing key com um serviço dá a esse serviço capacidade de consultar o saldo e o histórico correspondentes, embora não conceda autoridade de gasto.
6. **RPC de privacidade não pode usar fallback incompatível.** O endpoint Helius e a chave de API devem ser validados para Mainnet. Indexador, provador e chamadas privadas devem usar o serviço configurado e falhar fechados se ele estiver indisponível; não devem ser enviados ao RPC público secundário. O RPC secundário continua servindo apenas aos fluxos convencionais que já o utilizam.
7. **A revisão deve descrever a visibilidade real.** A confirmação precisa diferenciar público, depósito para privado, transferência privada e retirada para público. A UI não pode usar palavras como “anônimo” ou “totalmente privado” para o anel padrão.
8. **O coordenador permanece obrigatório.** Taxa, simulação, validade do blockhash, autorização, registro atômico do estado assinado e reconciliação continuam obrigatórios. Uma operação privada não pode contornar o Transaction Security Analyzer ou o TxCoordinator.

## Escopo visual desta etapa

- Exibir a visibilidade pública nos formulários de envio de SOL e tokens.
- Repetir a visibilidade pública na revisão, antes da assinatura.
- Incluir uma explicação contextual no índice de ajuda.
- Exibir em Ajustes uma folha informativa sobre o anel padrão e os limites de depósitos e retiradas.
- Identificar a integração como em desenvolvimento. Nenhuma transação privada é habilitada por esta etapa.

## Gates para habilitar operações privadas

- Fixar versão, origem, licença e hashes de todos os componentes Rust/NDK; validar os ABIs Android suportados.
- Demonstrar a geração e recuperação do ShieldedKeypair sem expor material secreto fora do módulo de custódia.
- Definir suporte ou indisponibilidade explícita para Local Secure Provider e Seed Vault.
- Implementar sincronização de UTXOs, descriptografia local, controle de nullifiers e recuperação após reinício, perda de conexão ou transação pendente.
- Construir instruções de depósito, transferência e retirada; integrá-las ao parser, à simulação e ao TxCoordinator.
- Validar o endpoint Helius, rotas `/v1/spr`, paginação, limites de API e tratamento de falhas sem fallback.
- Cobrir com testes automatizados e testes em aparelho físico as falhas de assinatura, prova, concorrência de UTXO, duplicidade, recuperação e exposição de dados.
- Só então habilitar o controle de privacidade e as operações de Mainnet.

## Consequências

A integração é uma alteração de segurança e de custódia, não apenas uma chamada RPC ou uma nova tela. A etapa visual torna explícita a situação atual e prepara o fluxo sem sugerir proteção inexistente. A funcionalidade de movimentação privada permanece bloqueada até que todos os gates sejam satisfeitos.

Nenhum teste ou build foi executado durante esta alteração, conforme solicitado pelo proprietário do projeto.