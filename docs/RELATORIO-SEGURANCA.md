# Relatório de segurança — Lock Wallet

Para: Christopher · Data: 19/11/2026 · App avaliado: **Lock Wallet (versão final)** — inclui tudo o que foi testado nas
rodadas anteriores. (Internamente o build se chama 5.0.0; o app não mostra número de versão.)

Este relatório explica, em linguagem simples, **quais ataques nós tentamos contra a Lock Wallet**, **como** cada um
foi tentado, **o que aconteceu** (bloqueado, ou encontrado e corrigido), **qual proteção existe hoje** e **o que ainda
é limite**. Não tem código; os detalhes técnicos ficam nos documentos internos do projeto (modelo de ameaças e ADRs).

---

## 1. Resumo em 1 minuto

- Foram feitas **quatro rodadas de ataque** (2.0, 3.0, 4.0 e a rodada final). Ao todo, **mais de 85 cenários** de ataque.
- Na 2.0 encontramos **1 problema alto, vários médios e alguns baixos** — **todos corrigidos**.
- Na 3.0 encontramos **2 médios e 2 baixos** — **todos corrigidos**.
- Na 4.0 atacamos o **novo stake** e o **visual transparente**: **2 pontos fracos pequenos** — corrigidos.
- Na **rodada final** atacamos de novo **todos os módulos**, com foco em casos “de aparelho” (memória cheia, contador de
  reinício ausente, relógio voltando, troca de carteira no meio de uma leitura) e nas novidades (ordem do stake por
  retorno, stake somado ao saldo, prazo da digital configurável e “Bloquear agora”). Encontramos **6 problemas (2 médios,
  4 baixos) e 1 ponto endurecido** — **todos corrigidos**, cada um com teste automático.
- **Nenhum ataque testado conseguiu**: tirar dinheiro sem você aprovar, ler sua frase secreta, assinar algo diferente do
  que você revisou, ou apagar sua carteira.
- **Limite honesto mais importante:** a Lock Wallet **não teve auditoria externa** (por uma empresa de segurança
  independente) e **não foi testada num celular físico com dinheiro real** dentro deste ambiente. Os testes rodaram num
  Android simulado. **Comece com valores pequenos.**

Como ler o resultado de cada ataque:
- ✅ **Bloqueado** — a proteção já existia e segurou.
- 🔧 **Encontrado e corrigido** — o ataque funcionava (total ou parcialmente); o defeito foi consertado e ganhou um
  teste automático que repete o ataque em toda versão nova.

---

## 2. Como os testes foram feitos

- **Testes automáticos de ataque:** pequenos programas que fazem o papel do atacante (um servidor da rede mentiroso,
  um site/dApp malicioso, um arquivo adulterado, um app espião etc.) e conferem que a carteira recusa. Eles rodam
  sozinhos em toda compilação — se alguém quebrar uma proteção no futuro, a compilação falha.
- **Fuzzing:** jogamos milhares de entradas aleatórias e “quase certas” nos leitores de dados (transações, arquivos de
  backup, cofre) para ver se algo trava ou aceita lixo.
- **Revisão manual** do código e do aplicativo final (o arquivo APK assinado): permissões, o que fica exposto para
  outros apps, backup do Android, registros (logs).
- **Conferência na rede real** (só leitura): endereços e dados dos validadores conferidos diretamente na rede Solana.

---

## 3. Ataques tentados — rodada 2.0

| # | Ataque | Como tentamos | Resultado | Proteção que existe hoje |
|---|---|---|---|---|
| 1 | **Site/dApp cobra uma taxa gigante** (esvaziar a carteira pela “taxa de prioridade”) | dApp falso pediu taxa enorme numa transação de aparência normal | 🔧 **Encontrado (alto) e corrigido** | Taxa acima de 0,005 SOL é **bloqueada**; taxa que não dá para calcular também; a tela de revisão mostra a taxa máxima |
| 2 | **Servidor mentiroso diz “sua transação expirou”** para você pagar duas vezes | servidor da rede falso declarou expiração falsa | 🔧 **Encontrado e corrigido** | Só aceitamos “expirou” com prova de **dois servidores**; senão fica “desconhecido” e o app não sugere reenviar |
| 3 | Servidor manda dados a mais na simulação para **travar o app** | resposta com contas extras | 🔧 **Corrigido** | Leitura estrita; resposta estranha vira erro amigável, sem travar |
| 4 | Transação **sem taxa de prioridade** não chega na rede congestionada (você tenta de novo e se confunde) | envio em rede cheia | 🔧 **Corrigido** | Taxa automática razoável (com teto de 0,001 SOL) |
| 5 | Enviar só para um servidor (se ele “engolir” a transação) | servidor primário mudo | 🔧 **Corrigido** | Os mesmos bytes vão para todos os servidores |
| 6 | Servidor fora do ar / limite de uso (erro 429) | simulamos queda e limite | 🔧 **Corrigido** | Troca automática para o servidor reserva, com espera |
| 7 | Erro inesperado **derruba o app** no meio de uma operação | forçamos falhas internas | 🔧 **Corrigido** | Mensagem amigável; nada é assinado pela metade |
| 8 | **Ver telas da carteira com o app bloqueado** (pulando a tela de bloqueio) | tentamos navegar direto para telas internas | 🔧 **Corrigido** | “Portão” rígido: bloqueado ⇒ só a tela de desbloqueio |
| 9 | **Frase secreta colada fica na área de transferência** (outros apps leem) | colamos a frase e lemos a área de transferência depois | 🔧 **Corrigido** | Ao colar, a área de transferência é apagada; teclado de senha |
| 10 | Resposta de **outro pedido** (servidor/proxy embaralha respostas) | respostas trocadas | 🔧 **Corrigido** | Cada resposta é conferida com o pedido |
| 11 | Servidor **diz que a taxa é menor** do que é | taxa falsa | 🔧 **Corrigido** | A carteira calcula sozinha e mostra a maior das duas |
| 12 | Link malicioso aberto pelo app | tentamos abrir sites arbitrários | 🔧 **Corrigido** | Só abre o explorador oficial da Solana |
| 13 | **Janelas separadas** (pop-ups) escapando da proteção contra print e sobreposição | revisão de todas as telas | 🔧 **Corrigido** | Todas as folhas e avisos são desenhados dentro da janela protegida |
| 14 | **Voltar para rede de teste** (saldo falso) | servidor de outra rede; dApp pedindo rede de teste | ✅ Bloqueado | Só rede principal; servidor conferido pela “impressão digital” da rede |
| 15 | dApp pede para assinar algo **disfarçado de mensagem** | “mensagem” que na verdade era transação | ✅ Bloqueado | Só texto legível é assinado como mensagem |
| 16 | Login falso (domínio diferente) | site com domínio trocado | ✅ Bloqueado/avisado | Aviso forte quando domínio não bate |
| 17 | **Programa desconhecido** numa transação de dApp | transação com programa estranho | ✅ Bloqueado/avisado | Programas centrais fora da lista ⇒ recusa; desconhecido ⇒ visão crua + confirmação + simulação obrigatória |
| 18 | **Token-2022 malicioso** (taxa escondida, “gancho”, delegado permanente) | tokens com extensões perigosas | ✅ Bloqueado | Proibido por padrão; só extensões inofensivas passam |
| 19 | **Envenenamento de endereço** (endereço parecido com um seu) | endereço falso quase igual | ✅ Avisado | Endereço inteiro em blocos, aviso de semelhança, confirmação no 1º envio |
| 20 | **Trocar quem controla o seu stake** | transação tentando mudar a autoridade | ✅ Bloqueado | Essa instrução não existe na lista permitida |
| 21 | **QR code malicioso** (pedido de pagamento escondido) | QR com pedido de transação | ✅ Bloqueado | Só o endereço é lido |

---

## 4. Ataques tentados — rodada 3.0

| # | Ataque | Como tentamos | Resultado | Proteção que existe hoje |
|---|---|---|---|---|
| 22 | **Abrir com a digital e ver a frase / exportar o backup** (só a tela impedia) | chamamos direto as funções internas numa sessão aberta pela digital | 🔧 **Encontrado (médio) e corrigido** | O próprio cofre recusa frase, backup e novas contas sem o **PIN** |
| 23 | **Editar o arquivo da digital** para ela valer para sempre (sem pedir PIN a cada 72 h nem após reiniciar) | editamos o arquivo e recalculamos a “soma de conferência” | 🔧 **Encontrado (médio) e corrigido** | O arquivo agora tem um selo feito com uma chave guardada no chip de segurança; editou ⇒ digital desligada, PIN funciona |
| 24 | **Gerador de números aleatórios quebrado** (frase previsível) | simulamos gerador que sempre devolve o mesmo valor | 🔧 **Encontrado (baixo) e corrigido** | A carteira se recusa a criar a frase |
| 25 | **Senha do backup cortada na tela** (você anota incompleta e perde o backup) | celular pequeno + fonte grande | 🔧 **Encontrado e corrigido** | Senha numa grade numerada que se adapta; teste prova que as palavras mostradas são exatamente a senha usada |
| 26 | Registro da digital **trocado entre carteiras** ou **depois de trocar o PIN** | copiamos/adulteramos registros | ✅ Bloqueado | Registro preso à carteira e ao PIN; adulterou ⇒ apagado, PIN funciona |
| 27 | **Nova digital cadastrada no celular** (por outra pessoa) | simulamos novo cadastro | ✅ Bloqueado | A digital da carteira é desligada; volta a pedir PIN |
| 28 | **Força bruta do PIN** | milhares de tentativas, inclusive matando o app no meio | ✅ Bloqueado | Espera crescente (até 24 h); tentativa contada antes de conferir; **nunca apaga** a carteira |
| 29 | **Cópia dos arquivos do cofre** + tentativa de adivinhar o PIN fora do celular | copiamos os arquivos | ✅ Bloqueado | Sem a chave do chip de segurança do celular os arquivos não abrem |
| 30 | **Arquivos do cofre trocados/misturados/antigos** | trocamos e voltamos versões | ✅ Bloqueado | Cada parte é amarrada às outras; não bate ⇒ não abre (nada apagado) |
| 31 | **Backup (arquivo LWRP) adulterado** ou senha errada | arquivos corrompidos/forjados, fuzzing | ✅ Bloqueado | Tamanho e formato exatos conferidos antes; criptografia que detecta qualquer alteração |
| 32 | **Outro app abrindo a carteira** por links/“intents” | intents forjados | ✅ Bloqueado | Só 2 portas abertas (abrir o app e conectar dApp); nada é assinado sem desbloqueio + revisão + PIN |
| 33 | **Print / gravação de tela / app espião por cima** (tapjacking) | captura e sobreposição | ✅ Bloqueado | Proteção contra print, toques por cima filtrados, janelas sobrepostas escondidas |
| 34 | **Backup do Android / transferência para outro celular** levando o cofre | revisão do APK | ✅ Bloqueado | Backup e transferência desligados |
| 35 | **Registros (logs)** vazando dados | busca automática no código | ✅ Bloqueado | Proibido registrar; nenhuma telemetria; nenhum servidor nosso |
| 36 | **Cotação falsa** numa das 10 moedas | fonte de preço mentirosa | ✅ Bloqueado | Faixa plausível por moeda; cotação nunca entra em assinatura |
| 37 | **Depurador / ferramentas de “hook”** | sinais conhecidos | ✅ Detectado (aviso) | Aviso na Central de Segurança (não é garantia) |
| 38 | Geração da frase e das chaves (checagem completa) | comparamos com 3 implementações independentes e vetores oficiais | ✅ Correta | Mesmos endereços que as referências |

---

## 5. Ataques tentados — rodada 4.0

O foco desta versão foi o **visual mais transparente** e o **novo stake com validadores recomendados**. Atacamos os dois.

| # | Ataque | Como tentamos | Resultado | Proteção que existe hoje |
|---|---|---|---|---|
| 39 | **Servidor mentiroso manda o mesmo validador duas vezes** (uma “fora do ar” e outra “saudável”) para enganar o bloco recomendado | resposta forjada com duplicata | 🔧 **Encontrado (baixo) e corrigido** | Resposta contraditória ⇒ validador **escondido** (falha fechado) |
| 40 | **Números absurdos** de stake para confundir o cálculo de concentração | stakes gigantes, negativos e comissões impossíveis | 🔧 **Encontrado (baixo) e corrigido** | Cálculo que não “estoura”; valor impossível ⇒ validador tratado como desconhecido |
| 41 | **Validador recomendado sai do ar** | marcamos como inadimplente | ✅ Bloqueado | Some do bloco com aviso; se você já tinha escolhido, a escolha é desfeita; a revisão também recusa |
| 42 | **Validador recomendado sobe a comissão** (ex.: de 0 % para 40 %) | comissão alterada | ✅ Bloqueado | Aparece “comissão subiu”, não pode ser escolhido; a revisão recusa com mensagem clara |
| 43 | **Trocar a máquina** por trás de um validador recomendado (identidade diferente) | identidade trocada | ✅ Bloqueado | Tratado como desconhecido e escondido |
| 44 | **Lista na tela velha** (escolher algo que mudou há minutos) | mudamos a rede entre a escolha e a revisão | ✅ Bloqueado | A lista é relida a cada 60 s e **de novo na hora da revisão** |
| 45 | **Stake líquido escondido** (trocar stake nativo por um “pool” ou token de recibo) | conferimos as instruções geradas | ✅ Bloqueado | Só 3 passos nativos: criar conta, iniciar, delegar — autoridade sempre você |
| 46 | **Escolha “avançada” perigosa** (colar endereço de validador desconhecido) | colamos endereços quaisquer | ✅ Avisado | Aviso forte no modo avançado e de novo na revisão; endereço precisa existir na rede |
| 47 | **Vidro transparente deixando texto ilegível** (você lê errado um valor/endereço) | medimos o contraste nos 2 temas, inclusive com cor oposta atrás | ✅ Aprovado | Texto principal com contraste ≥ 7:1 mesmo no pior caso; teste automático trava se piorar |
| 48 | **Transparência mostrando a frase/valores por trás** de uma folha | revisão das telas | ✅ Bloqueado | O que fica atrás é desfocado; proteção contra print continua; nada novo é exibido |

---

## 6. Ataques tentados — rodada final

Foco: tudo de novo, com olhar de atacante, mais os pontos fracos apontados na nota 6,5/10 que dava para atacar no código.

| # | Ataque | Como tentamos | Resultado | Proteção que existe hoje |
|---|---|---|---|---|
| 49 | **Trocar de carteira (ou de rede) enquanto o saldo ainda está carregando** — a resposta atrasada da carteira antiga aparecia na nova | simulamos a troca no meio da leitura | 🔧 **Encontrado (baixo) e corrigido** | Cada leitura tem uma “senha de geração”; resposta de antes da troca é descartada |
| 50 | **Valor gigante digitado** (perto do limite do número) para “estourar” a conta saldo + taxa e passar pela checagem | valores de 9 bilhões de SOL e mais | 🔧 **Encontrado (baixo) e corrigido** | Conta feita de forma que não estoura + teto de 1 bilhão de SOL; nada chega à revisão. (A rede recusaria de qualquer jeito; ninguém perderia dinheiro) |
| 51 | **“1.000” lido como 1 SOL** (no Brasil, “1.000” é mil) | digitamos valores com ponto de milhar | 🔧 **Encontrado (baixo) e corrigido** | “1.000”, “12.500” etc. são recusados como **ambíguos**, com a dica “use vírgula para decimais” |
| 52 | **Digital depois de reiniciar** num celular que **não informa o contador de reinícios** (alguns fabricantes) | contador ausente + esperar o celular ficar ligado mais tempo que antes | 🔧 **Encontrado (médio) e corrigido** | Sem contador ⇒ a carteira **sempre pede o PIN** depois de qualquer dúvida; o PIN sempre funciona |
| 53 | **Coação com a digital** (forçar seu dedo) dentro de uma janela longa de 72 h | revisão do desenho + testes | 🔧 **Endurecido** | Prazo padrão agora **24 h** (você escolhe 1, 4, 24 ou 72 h); **aumentar o prazo exige o PIN**; botão **“Bloquear agora”** desliga a digital até o próximo PIN |
| 54 | **Editar o arquivo da digital** para aumentar o prazo ou apagar o “Bloquear agora” | editamos o arquivo e recalculamos somas | ✅ Bloqueado | O arquivo é selado com chave do chip de segurança; editou ⇒ digital desligada, PIN funciona |
| 55 | **Arquivo antigo da digital** (de versões anteriores, sem prazo) | carregamos o formato antigo | ✅ Bloqueado | Recebe o padrão mais rígido (24 h) |
| 56 | **Relógio do celular voltando** (ou falha do relógio) para esticar o prazo da digital | voltamos o relógio interno | ✅ Bloqueado | Relógio voltou ⇒ pede o PIN; o relógio de parede nunca é usado |
| 57 | **Memória cheia / disco que não grava** na hora de contar a tentativa de PIN (para tentar PINs “de graça”) | bloqueamos a gravação do contador | ✅ Bloqueado | Se a tentativa não pode ser contada, **o PIN nem é conferido**; nada é apagado; quando a gravação volta, tudo funciona |
| 58 | **Servidor inventa o desempenho de um validador** para colocá-lo no topo da lista de stake | recompensas absurdas, negativas, repetidas | ✅ Bloqueado | O desempenho é comparado com a mediana da rede (pesada por stake) e **limitado a 100 %**; dado estranho ⇒ “sem dado”, nunca vantagem; segurança sempre vem antes do retorno |
| 59 | **Servidor esconde a comissão real** (porcentagem baixa, comissão fina alta) | dois campos de comissão diferentes | ✅ Bloqueado | Vale **sempre a maior** das duas; comissão que subiu ⇒ bloqueado |
| 60 | **Conta de stake de outra pessoa “aparecendo” no seu saldo** (inflar o total) | conta de stake com você como “operador” mas outra pessoa como dona | ✅ Bloqueado | Só entra no total o stake em que **você** pode sacar |
| 61 | **Recompensa falsa de stake** | recompensa maior que a conta, de outra época, negativa | ✅ Bloqueado | Dado inconsistente ⇒ a linha de recompensa **some** (nunca mostra número inventado) |
| 62 | **“Enviar tudo” usando o stake** (tentar gastar o que está preso) | enviar o máximo com stake no saldo | ✅ Bloqueado | “Máximo” usa só o **Disponível**; acima disso é recusado |
| 63 | **Leitura do stake falha** e o saldo total fica errado | servidor sem resposta de stake | ✅ Bloqueado | Mostra só o disponível e avisa que o total está incompleto |
| 64 | **Respostas malformadas** de validadores (campos com tamanho errado, comissão fora de 0–100 %, listas enormes) | fuzzing dirigido | ✅ Bloqueado | Leitura estrita; lista limitada; resposta estranha ⇒ erro amigável, nada assinado |
| 67 | **Resposta real da rede principal com uma “linha sentinela”** (número máximo de 64 bits) num validador fora do ar | conferimos com a resposta real da rede, baixada hoje | 🔧 **Encontrado (médio) e corrigido** | Antes da correção, a leitura rígida recusava a lista inteira e o stake não carregaria. Agora a linha sentinela é ignorada e o resto é lido normalmente; teste com o formato real |
| 68 | **Mudança de unidade da rede (Alpenglow)**: desde 21/10/2026 o contador da conta de voto guarda **recompensa em lamports**, não votos — validador maior parecia “melhor” | comparamos com os dados reais de 685 validadores | 🔧 **Encontrado (baixo) e corrigido** | Desempenho medido **por SOL em stake** contra a mediana da rede; épocas antigas (outra unidade) ignoradas |
| 65 | **Número da versão revelado na tela** (ajuda um atacante a mirar defeitos de uma versão) | varremos todas as telas e textos de ajuda | ✅ Aprovado | Nenhuma versão aparece no app |
| 66 | **Tela pequena + fonte gigante** escondendo parte do saldo/endereço | 320 dp de largura, fonte 200 % | ✅ Aprovado | Valores encolhem para caber; nada é cortado |

Também foram repassados, com resultado igual ao das rodadas anteriores: portas abertas para outros apps (só 2),
links e QR, Lock Connect (dApps), backup LWRP, regras de compilação (R8 remove registros), permissões do APK e o
histórico inteiro do repositório (sem chaves, senhas ou keystore).

---

## 7. O que protege você hoje (em uma lista)

1. **Nada é assinado sem você ver e aprovar** — e o que é assinado é exatamente o que foi mostrado (conferido duas vezes).
2. **Lista fechada** do que a carteira aceita assinar; o resto é recusado ou mostrado “cru” com aviso.
3. **Simulação obrigatória** antes de assinar.
4. **Frase e chaves só no seu celular**, cifradas com chave do chip de segurança + PIN (Argon2id).
5. **PIN errado nunca apaga** a carteira; só faz esperar.
6. **Digital opcional**, com PIN obrigatório após reiniciar, após erro de PIN, a cada **24 h** (padrão; 1/4/24/72 h) e
   para ver frase/exportar; **“Bloquear agora”** força o PIN na hora.
7. **Sem servidor nosso, sem telemetria, sem registros.**
8. **Só rede principal**, conferida.
9. **Token-2022 perigoso bloqueado por padrão.**
10. **Stake só nativo**, autoridade sempre sua, validadores recomendados conferidos ao vivo.
11. **Proteção contra print e contra apps por cima.**

---

## 8. Limites que continuam (honestamente)

- **Sem auditoria externa.** Todas as revisões foram internas. Para dinheiro grande, uma auditoria independente é o
  próximo passo recomendado.
- **Não testado num celular físico com fundos reais** neste ambiente; os testes rodam num Android simulado. Faça o teste
  manual da lista de lançamento com **valores pequenos** antes de usar de verdade.
- **Celular com root ou sistema comprometido** pode ver tudo o que o app mostra e mexer no app. Nenhuma carteira de
  celular resolve isso por completo.
- **Coação com a digital:** se a digital estiver ligada, dentro do prazo escolhido (padrão 24 h) alguém pode forçar seu
  dedo e **assinar** (não pode ver a frase nem exportar). Diminua o prazo, use **“Bloquear agora”** antes de situações
  de risco, ou deixe a digital desligada.
- **PIN de 6 dígitos é curto** fora do celular; a proteção real vem do chip de segurança do aparelho.
- **Servidores da rede podem mentir sobre o saldo mostrado** (não podem roubar, mas podem mostrar número errado). Se os
  dois servidores padrão combinarem uma mentira, a carteira não percebe.
- **Validador recomendado é opinião informada, não garantia.** Um validador pode subir a comissão **depois** que você
  delegou: o app passa a mostrar a nova comissão, mas não move seu stake sozinho (você decide desativar e delegar de
  novo).
- **Você pode ignorar avisos** (endereço parecido, programa desconhecido, modo avançado). A carteira avisa, mas a decisão
  final é sua.
- **Senha fraca do backup** deixa o arquivo de backup vulnerável; use a senha gerada de 8 palavras.
- **Detector de depurador/hook** é só um aviso; um atacante habilidoso pode escondê-lo.

---

## 9. Recomendações práticas

1. Use primeiro com **valores pequenos**; depois aumente aos poucos.
2. **Anote a frase no papel** (nunca foto) e guarde também o backup com a senha gerada.
3. Deixe a **digital desligada** se você teme ser forçado a desbloquear — ou use prazo de 1 h e “Bloquear agora”.
4. No stake, prefira o **bloco recomendado**; use o **Avançado** só se souber exatamente para quem está delegando.
5. Sempre confira o **endereço inteiro** antes de enviar.
6. Antes de colocar valores altos, considere uma **auditoria externa**.
