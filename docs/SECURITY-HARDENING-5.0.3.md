# Endurecimento de segurança — Lock Wallet 5.0.3

**Estado:** em validação; este documento não certifica uma auditoria independente nem substitui o teste em aparelho físico.

## Alterações implementadas na branch de preparação

### Diário de transações (TxJournal)
- O conteúdo persistido agora é autenticado com HMAC-SHA-256, usando uma chave não exportável do Android Keystore e separação de domínio.
- A leitura verifica a autenticação antes de interpretar os registros. Chave ausente, inacessível, tag inválida ou conteúdo adulterado faz a operação falhar fechada.
- A ausência do arquivo após a criação da chave também é detectada; o diário não passa silenciosamente a ser considerado vazio.
- A migração do JSON sem autenticação da 5.0.2 descarta os bytes da transação assinada, marca os registros como `UNKNOWN` e exige reconciliação com a blockchain antes de qualquer decisão de reenvio.
- Escritas usam arquivo temporário, sincronização, leitura de retorno, verificação e renomeação atômica.
- **Limite explícito:** HMAC não impede restaurar uma cópia antiga completa e válida do arquivo. O diário não declara proteção anti-rollback por hardware; estado de transação continua exigindo reconciliação on-chain.

### Cofre biométrico (BioVault)
- Registros novos usam um alias Keystore exclusivo por carteira, em vez de compartilhar a chave RSA privada de todos os registros.
- Cada registro mantém um arquivo de referência de chave por carteira (`.keyref`) durante todo o seu ciclo de vida. Isso permite localizar o alias mesmo quando o ciphertext está truncado ou corrompido.
- Ao remover uma carteira, a referência e a chave exclusiva são processadas e a ausência da chave é confirmada antes da exclusão do ciphertext.
- Ao desativar a biometria, aliases identificados pelo estado, arquivos `.keyref`, registros e temporários são coletados; as chaves são removidas e verificadas antes da limpeza dos arquivos.
- A chave compartilhada dos registros legados da 5.0.2 não permite eliminação criptográfica individual. Ao remover um registro legado, a migração segura desativa a biometria e apaga os registros biométricos, sem apagar as carteiras; o PIN continua sendo o caminho de acesso.
- **Limite explícito:** a API do Keystore confirma a remoção do alias, mas não permite prometer sobrescrita física de células de memória flash. Esta é eliminação criptográfica lógica, não uma garantia de sanitização física do armazenamento.

### RPC customizado e DNS rebinding
- O hostname é resolvido uma vez por requisição; todos os endereços A/AAAA retornados são verificados. Uma resposta vazia ou contendo qualquer IP privado/especial é recusada.
- A conexão TCP é aberta diretamente para um dos objetos de endereço já validados, sem segunda resolução de DNS.
- O TLS mantém o hostname original para SNI e validação do certificado. Redirecionamentos não são seguidos; qualquer nova tentativa/requisição faz nova resolução e validação.

### KDF Argon2id e armazenamento da seed
- O perfil atual continua em Argon2id v=0x13 com saída de 32 bytes, salt aleatório de 16 bytes e parâmetros `m=19 MiB, t=2, p=1`, que correspondem ao piso de parâmetros da OWASP Password Storage Cheat Sheet.
- O parâmetro foi auditado, mas não alterado nesta versão: LWLE v1 e LWRP v1 gravam/esperam perfis versionados. Trocar os números sob o mesmo identificador tornaria carteiras ou Recovery Packages antigos ilegíveis.
- Um perfil mais forte exige novo identificador de KDF/formato, leitura dos perfis antigos e migração atômica somente após desbloqueio bem-sucedido. A escolha de parâmetros reforçados depende de benchmark no aparelho Android suportado de menor capacidade; não há neste ambiente medição física que justifique impor esse custo.
- A revisão do envelope atual confirmou a hierarquia: material cifrado por AES-256-GCM sob DEK aleatória; DEK protegida por chave derivada do PIN via Argon2id e AES-GCM; o resultado protegido novamente pela KEK do Android Keystore. AAD usa domínios separados e é vinculada ao cabeçalho; ao abrir, a chave pública é derivada novamente e conferida.
- Não foi identificado motivo técnico para reescrever a hierarquia criptográfica ou mudar os bytes do formato existente nesta versão. Os vetores independentes e testes de alteração de bytes já existentes permanecem como critérios de regressão.

## Portões de validação
- Fixtures de PIN corrigidos em `AppFlowTest`, `ConnectFlowTest` e `BiometricUnlockTest`.
- Testes adicionados para adulteração, chave ausente, remoção do diário, recuperação de temporário autenticado, rejeição de downgrade, migração legada, eliminação biométrica por carteira e interrupções de escrita, além de respostas DNS públicas/privadas/mistas.
- O workflow `security-ci.yml` executa `./gradlew ciCheck` em cada atualização da pull request.
- **Aprovação pendente até que o workflow execute com sucesso no commit final**, incluindo todos os testes, compilação debug/release e verificações do projeto.

## Riscos residuais que não podem ser declarados resolvidos por esta alteração
1. Ameaça T-58 do modelo: ainda não há auditoria independente de terceiros nem ensaio em aparelho físico com fundos reais. Uma suíte interna, mesmo verde, não fecha esse risco.
2. HMAC do TxJournal detecta adulteração, mas não restauração de um snapshot antigo integralmente válido; não há contador anti-rollback disponível nesta implementação.
3. Dispositivo com root, sistema operacional comprometido, malware com controle efetivo do processo ou coação física continuam fora da garantia absoluta de uma carteira móvel.
4. Eliminação de arquivos em memória flash não equivale a sanitização física garantida.

**Política de release:** não publicar a 5.0.3 enquanto o `ciCheck` do commit final não passar e o APK release não tiver manifesto, assinatura/certificado, checksum e SBOM verificados.
