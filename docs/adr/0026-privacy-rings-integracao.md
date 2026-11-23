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

## Descobertas técnicas da revisão de integração (2026-11-22)

- O SDK publicado é composto por crates Rust (por exemplo, `spr-client`, `spr-keypair`, `spr-transaction` e interfaces do protocolo); a Helius também publica SDK TypeScript. Não foi encontrado um binding Android/Kotlin oficial no repositório do SDK.
- O tipo `ShieldedKeypair` deriva a viewing key e a nullifier key a partir da chave Ed25519, usando uma assinatura determinística de domínio `TSPP/derive/v1`. Para a carteira local, essa operação teria de acontecer dentro da fronteira de custódia; a seed ou o segredo Ed25519 não podem ser enviados à UI nem persistidos em claro.
- O `SeedVaultProvider` atual só permite a política de mensagens legíveis (`MessagePolicy`) em operações de mensagem dApp. A mensagem binária/de domínio usada pela derivação do Privacy Ring não está autorizada por esse caminho. Não se deve ampliar essa política globalmente; eventual suporte Seed Vault exige uma API de custódia específica, com payload exato, domínio conferido e validação de assinatura.
- O SDK possui `Submission::finish_unsigned_sync`, que permite obter a mensagem sem assiná-la. Esse caminho é preferível ao envio interno do SDK porque pode preservar a assinatura da LockWallet e o `TxCoordinator`; ainda assim, o conteúdo precisa ser convertido para o formato suportado pela carteira e analisado por um parser tipado antes de qualquer assinatura.
- A rota de prova remota usa política TEE assinada e verificável; a integração precisa fixar e validar essa política para Mainnet. `ProofDataSource::Client` determina de onde vêm os dados de prova e, por si só, **não** significa que a geração da prova ZK seja local. Uma implementação de provador no dispositivo é uma tarefa separada.
- O cliente de referência usa `spr-client 0.10.0`, `spr-keypair 0.10.0` e crates Solana recentes. A compatibilidade binária com os módulos Kotlin/JVM atuais e os targets Android NDK ainda não foi demonstrada. Não adicionar os crates à aplicação sem fechar essa compatibilidade, o lockfile, a verificação de dependências e um build Android reproduzível.
- O anel permissionless da Mainnet é confidencial, não anônimo: ativo e quantidade são criptografados, mas as carteiras permanecem visíveis. Custom Rings com anonimato dos participantes continuam em auditoria segundo o anúncio oficial consultado nesta data.

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

## Verificação técnica do SDK upstream — escopo A (2026-11-22)

A revisão do código-fonte upstream foi repetida contra o commit imutável
`helius-labs/privacy-rings@42de575202f07adf6cfe9194ae9227445797bd8e`.
Esse snapshot declara Rust `1.98.1` em `rust-toolchain.toml`. Apesar de a
documentação global mais recente usar o nome comercial `zolana-client`, o
workspace Rust consultado ainda declara os crates `spr-client`,
`spr-keypair` e `spr-transaction`, todos na versão `0.10.0`. A integração
deve fixar o commit e conferir os nomes efetivamente publicados; não depender
de nomes de pacote deduzidos de documentação que pode estar em transição.

### Fluxo Default Ring confirmado no SDK

- O primeiro depósito é uma instrução pública `Deposit`; não há UTXOs privados
  prévios a gastar. O SDK de exemplo monta essa instrução separadamente. A
  revisão deve exibir o ativo, o valor e o endereço público que financia o
  depósito.
- Depois do depósito, `UtxoScan` lê as transações indexadas pelas tags da
  carteira e descriptografa os UTXOs no cliente com `ShieldedKeys`. O código
  do scanner consultado declara explicitamente que os payloads de transferências
  anônimas e ring-deposit não são suportados; isso não impede o escopo A.
- Transferências e retiradas usam `ConfidentialTransaction`, geram
  `SppProofInputs` e passam por `Submission::finish_unsigned_sync`. A
  LockWallet deve receber a mensagem não assinada, analisá-la, mostrá-la,
  simular e só então obter a assinatura da custódia. Não usar
  `Submission::send_sync` nem qualquer caminho que assine ou envie diretamente
  pelo SDK.
- `UtxoScan` e `LocalShieldedKeys` permitem manter a descriptografia e o
  cálculo de nullifiers no cliente. Não enviar viewing keys ou nullifier keys ao
  indexador/provador, não delegar a visualização por padrão e não gravar as
  chaves em preferências, logs ou arquivos sem a proteção do cofre.

### Derivação de chaves

O código upstream `spr-keypair` deriva os papéis a partir da assinatura
Ed25519 da mensagem binária de domínio `TSPP/derive/v1`, codificada no formato
off-chain Solana v0. Essa assinatura é material secreto equivalente à raiz de
derivação das chaves de visualização e nullifier; não é uma mensagem pública
comum. A LockWallet precisa de uma operação dedicada dentro de
`:custody:local`, que confira internamente domínio, endereço e assinatura,
e entregue o resultado somente à fronteira nativa que mantém as chaves em
memória. Não ampliar globalmente `MessagePolicy`, não expor a assinatura à UI
e não exportar seed ou chave privada.

### Provas e serviços remotos

Para Mainnet, o SDK atual oferece `SignedTeePolicy::for_network(Mainnet)`
e `ProverClient::with_signed_tee`. A integração não pode usar um provador
remoto sem política assinada e atestação verificada. O fluxo deve falhar
fechado em caso de policy ausente, expirada, inválida ou de divergência na
atestation; sem fallback para HTTP ou serviço sem TEE. O uso de
`ProofDataSource::Client` faz a carteira buscar os witnesses no cliente,
mas não transforma a geração da prova em local: o pedido de prova ainda segue
para o provador remoto, protegido pela sessão TEE.

O endpoint Helius usado para RPC, indexador e provador deve ser configurado
explicitamente para Privacy Rings. Não encaminhar chamadas `/v1/spr` ao RPC
público secundário nem tratar a falha de indexador/provador como saldo zero.
A chave de API não deve ser embutida no código, em repositório ou em logs.

### Compatibilidade com a LockWallet

O SDK retorna `UnsignedMessage` com `VersionedMessage` e seu
`last_valid_block_height`. O analisador Kotlin atual usa `LegacyMessage` e
não decodifica esse formato. Antes de habilitar o recurso, será necessário um
caminho tipado de análise do formato versionado e das instruções SPP, com
validação de programa, discriminador, contas, permissões, owner signers,
settlement transfers, valores públicos de depósitos/retiradas, compute budget,
fee payer e efeitos esperados. A assinatura deve continuar vinculada ao hash
exato que foi revisado. A máquina `TxCoordinator` continua responsável por
simulação, validade do blockhash, persistência da assinatura antes do envio,
broadcast e reconciliação.

A versão do SDK exige Rust `1.98.1`; a ponte Android/JNI e os targets Android
NDK ainda não foram compilados ou testados neste repositório. Nenhum fluxo
privado deve ser habilitado antes de haver build Android reproduzível, testes
herméticos do parser/FFI, vetores oficiais de derivação, testes de recuperação
de carteira, teste de integração com prover TEE Mainnet e testes em aparelho.


### Persistência local e histórico confidencial

O `TxJournal` atual autentica integridade por MAC, mas seu payload é JSON
legível; MAC não é criptografia. Valores, ativos, saldos e histórico de uma
transferência confidencial não podem ser gravados nesse journal como campos
legíveis, nem aparecer em logs, relatórios de crash, analytics ou exportações.
O histórico do Default Ring deve usar armazenamento local cifrado sob chave
derivada/protegida pela custódia, com política de backup e restauração
explícita. O journal público existente pode guardar assinatura, estado,
blockhash e dados efetivamente públicos da transação, mas campos locais de
quantia/ativo confidenciais devem ser omitidos ou substituídos por referências
opacas. Quando a camada cifrada estiver indisponível ou não autenticada, o app
não deve mostrar saldo/histórico como zero nem tentar reconstruí-los a partir
de dados públicos: deve apresentar indisponibilidade e preservar os fundos.

A integração deve também validar os limites e a finalidade de cada serviço:
RPC Solana para estado público e envio de transações; indexador Privacy Ring
para eventos/UTXOs cifrados; provador TEE para provas. Erro, resposta inválida,
atraso de sincronização, policy/attestation inválida ou divergência de estado
devem produzir falha explícita e bloquear a assinatura, nunca fallback
silencioso para um serviço público ou resultado vazio.


## Implementação inicial Default Ring — 2026-11-22

Esta seção atualiza o estado de implementação e prevalece sobre os gates
marcados como pendentes nas seções anteriores.

A integração inicial foi incorporada ao código com escopo deliberadamente
restrito:

- Mainnet Solana, endpoint Helius HTTPS com `api-key`, RPC/indexador/provador
  Helius e política TEE assinada oficial para Mainnet.
- Apenas `LOCAL_SECURE`; SeedVault e carteiras Connect não são elegíveis.
- Apenas SOL. SPL e Token-2022 permanecem desabilitados até existir revisão de
  settlement/ATA e cobertura de testes correspondente.
- Operações expostas: depósito público para o Default Ring, transferência
  confidencial entre Shielded Addresses e retirada pública.
- Não é anonimato: os endereços continuam públicos. Depósitos e retiradas
  revelam publicamente endereço, ativo e valor.

### Ponte e custódia

O novo crate `native/privacy-ring` fixa o SDK Rust no commit
`42de575202f07adf6cfe9194ae9227445797bd8e` e é empacotado como biblioteca
JNI para `arm64-v8a` e `x86_64`. O toolchain Rust é fixado em 1.98.1.

A custódia local assina a mensagem binária de derivação TSPP somente depois de
validar a carteira e exigir PIN. Apenas essa assinatura determinística, não a
seed nem a chave privada, cruza a fronteira JNI. O lado Rust valida a assinatura
contra a chave pública, expande os papéis viewing/nullifier e mantém essas
chaves somente em memória enquanto a sessão está aberta. Sair da tela, bloquear
o app ou cancelar a sessão fecha o handle nativo; operações preparadas são
descartadas e os buffers de transação são zerados.

A ponte usa `UtxoScan`, `LocalShieldedKeys`,
`ConfidentialTransaction` e `Submission::finish_unsigned_sync`. O SDK não
assina nem transmite. A assinatura final volta a ocorrer dentro de
`:custody:local`, após PIN separado e validação nativa de bytes, hash,
operação e uso único. O envio fica no `TxCoordinator`.

### V1, revisão e journal

A mensagem Solana V1 usa um caminho distinto do analisador Legacy. O serializador
V1 da carteira coloca a mensagem seguida da única assinatura de 64 bytes, sem
prefixo de quantidade Legacy, respeitando o limite de 4.096 bytes. Mensagens
V1 não são convertidas em Legacy nem passam pelo parser antigo.

O coordenador valida Mainnet, hash de rede, blockhash, taxa e simulação antes
da autorização; após a segunda confirmação de PIN, confere novamente a validade
do blockhash, grava o registro assinado antes do broadcast e transmite os mesmos
bytes nos endpoints configurados. A reconciliação continua usando a máquina de
estados existente.

O journal registra assinatura, estado, fee, blockhash e somente informações
públicas/operacionais. Campos `amountUnits`, `decimals` e `mint` ficam nulos
para operações Privacy Ring. O saldo confidencial é consultado novamente no
indexador e descriptografado localmente; não é salvo como saldo JSON em texto
legível.

### Validação

Foi adicionado workflow GitHub Actions para compilar a biblioteca nativa,
executar testes Rust, testes dos módulos Kotlin e gerar APK debug. O workflow
ainda precisa executar na branch após o commit. Até o resultado verde, e antes
de teste integrado em Mainnet com quantias mínimas controladas e revisão de
segurança independente, a implementação deve ser tratada como candidata de
integração, não como auditada ou garantida contra todos os riscos.

A primeira versão não afirma suporte a SPL, SeedVault, anonimato dos endereços,
visualização sem Helius ou funcionamento offline. Erros de RPC, indexador,
prova/TEE, simulação, blockhash ou custódia bloqueiam a assinatura; não há
fallback para envio público comum nem saldo presumido como zero.

## Endpoint dedicado — 2026-11-23 (5.0.7)

A decisão 6 passa a valer integralmente na interface. O Default Ring usa um
endpoint Helius dedicado, configurado em Ajustes → Saldo confidencial e
validado antes de salvar: HTTPS, host `helius-rpc.com` (ou subdomínio) com
`api-key` não vazia e Genesis Hash da Mainnet conferido no próprio endpoint
(a mesma validação da ponte nativa, aplicada antes de qualquer chamada).
O campo é separado do servidor da rede comum: o tráfego `/v1/spr`
(indexador e provador) nunca usa o RPC comum nem o secundário, e o RPC comum
nunca é promovido a endpoint de privacidade. Sem endpoint configurado, a rota
bloqueia com instrução clara (fail-closed) antes mesmo de pedir o PIN; salvar
ou remover o endpoint encerra qualquer sessão confidencial ativa. A URL com a
api-key fica nas preferências locais do aparelho, no mesmo padrão já
documentado para o RPC comum; a tela mostra apenas o host, nunca a chave.
Migração única: um RPC principal Helius configurado antes desta versão é
copiado uma única vez para o campo dedicado; o RPC comum permanece inalterado.
