# TempoKV — Plano de Implementação por Etapas

> Oito marcos incrementais até uma release de portfólio completa.

| Arquitetura-alvo: servidor single-node em Java 25, distribuído por JAR e Docker, com Java NIO, storage MVCC, WAL, SQL via JFlex/CUP e replicação primário-réplica. |
|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|

Versão arquitetural 1.0 • Documento consistente com os demais artefatos da série

# 1. Estratégia de execução

O plano prioriza fatias verticais verificáveis. Cada etapa termina com um fluxo de produto executável, testes e critérios de saída. As classes citadas são exatamente as classes do diagrama conceitual; os fluxos são exatamente os casos de uso do documento correspondente.

> Nota de leitura
> Não iniciar SQL, persistência ou replicação antes de o pipeline RESP → Command → Handler → Storage estar estável. A arquitetura prevê as extensões desde o início, mas a implementação permanece incremental.

# 2. Visão geral das etapas

| **Etapa** | **Marco**                                                | **Casos de uso**                                       | **Classes novas** | **Classes alteradas** |
|-----------|----------------------------------------------------------|--------------------------------------------------------|-------------------|-----------------------|
| E1        | Fundação, configuração e ciclo de vida                   | UC-00                                                  | 7                 | 0                     |
| E2        | Servidor NIO, RESP e pipeline administrativo             | UC-02                                                  | 18                | 3                     |
| E3        | Comandos key-value e armazenamento versionado atual      | UC-03                                                  | 12                | 7                     |
| E4        | Operações temporais, restauração e retenção              | UC-05, UC-06, UC-07                                    | 4                 | 10                    |
| E5        | Durabilidade, recuperação, expiração ativa e compactação | UC-01, UC-10, UC-11, UC-03, UC-07, UC-08               | 10                | 16                    |
| E6        | Front-end SQL com JFlex e Java CUP                       | UC-04, UC-05, UC-06, UC-07                             | 11                | 10                    |
| E7        | Transações, segurança e observabilidade operacional      | UC-08, UC-09, UC-12, UC-03, UC-04, UC-05, UC-06, UC-07 | 7                 | 18                    |
| E8        | Replicação, hardening e publicação de portfólio          | UC-13, UC-00, UC-01, UC-11, UC-12                      | 7                 | 21                    |

# E1 — Fundação, configuração e ciclo de vida

| **Objetivo**              | Transformar o repositório em um produto Java executável, configurável e empacotável, ainda sem protocolo de banco. |
|---------------------------|--------------------------------------------------------------------------------------------------------------------|
| **Casos de uso cobertos** | UC-00                                                                                                              |

## Descrição detalhada

- Migrar para Gradle, Java 25, estrutura modular por pacotes e testes JUnit.

- Definir configuração por argumentos, variáveis de ambiente e arquivo opcional, com precedência documentada.

- Implementar ciclo de vida STARTING/READY/STOPPING e encerramento ordenado.

- Criar abstração de filesystem e lock exclusivo do diretório de dados.

- Disponibilizar JAR executável, Dockerfile multi-stage e docker-compose de nó único.

- Criar métricas e health mínimos para o bootstrap.

## Classes criadas nesta etapa

| **Classe**          | **Módulo**    | **Responsabilidade nesta etapa**                                                                                          |
|---------------------|---------------|---------------------------------------------------------------------------------------------------------------------------|
| TempoKvApplication  | bootstrap     | Ponto de entrada do processo. Carrega configuração, monta o grafo de componentes e inicia o ciclo de vida do nó.          |
| ServerConfiguration | bootstrap     | Modelo imutável das opções do nó: portas, diretório de dados, papel primário/réplica, retenção, persistência e segurança. |
| TempoKvServer       | bootstrap     | Orquestra inicialização, recuperação, endpoints, workers e encerramento ordenado de uma instância TempoKV.                |
| FileSystemAdapter   | persistence   | Centraliza operações de arquivo, rename atômico, locks, sync e simulação de falhas em testes.                             |
| DatabaseLock        | persistence   | Mantém lock exclusivo sobre o diretório de dados de uma instância.                                                        |
| MetricsRegistry     | observability | Agrega contadores, latências, tamanhos e estados usados pelos comandos administrativos.                                   |
| ServerHealthService | observability | Calcula estados STARTING, RECOVERING, READY, DEGRADED e STOPPING a partir dos subsistemas.                                |

## Fluxo validado

- JAR e container iniciam com diretório vazio, adquirem o lock e encerram liberando recursos.

- Uma segunda instância sobre o mesmo diretório é rejeitada.

- Configurações inválidas impedem abertura de portas.

## Testes obrigatórios

- Testes de precedência e validação de configuração.

- Teste de lock concorrente em diretório temporário.

- Teste de lifecycle e shutdown hook.

- Smoke test do JAR e da imagem Docker.

## Critérios de saída

- Build reproduzível em ambiente limpo.

- Nenhum arquivo compilado versionado.

- UC-00 executável no modo sem endpoints.

- README contém comandos de build e execução.

## Entregáveis

- Gradle wrapper

- Dockerfile

- docker-compose.yml

- configuração documentada

- estrutura inicial de pacotes

- pipeline CI básico

# E2 — Servidor NIO, RESP e pipeline administrativo

| **Objetivo**              | Aceitar múltiplos clientes Redis e executar PING por um pipeline já compatível com a arquitetura final. |
|---------------------------|---------------------------------------------------------------------------------------------------------|
| **Casos de uso cobertos** | UC-02                                                                                                   |

## Descrição detalhada

- Implementar event loop NIO, buffers por conexão, leitura parcial, escrita pendente e fechamento seguro.

- Implementar RESP2 suficiente para arrays, bulk strings, simple strings, inteiros, nulos e erros.

- Criar sessão por conexão e interfaces de autenticação/autorização inicialmente permissivas.

- Criar modelo comum de Command, dispatcher, validação, handler e CommandResult.

- Mapear PING para AdminCommand e responder em pipeline.

- Medir conexões e latência básica.

## Classes criadas nesta etapa

| **Classe**            | **Módulo**    | **Responsabilidade nesta etapa**                                                                                             |
|-----------------------|---------------|------------------------------------------------------------------------------------------------------------------------------|
| RespServer            | server        | Endpoint TCP compatível com RESP. Aceita conexões Redis e delega cada sessão ao handler apropriado.                          |
| NioEventLoop          | server        | Gerencia sockets não bloqueantes, leitura parcial, escrita pendente e backpressure para múltiplos clientes.                  |
| ClientConnection      | server        | Representa uma conexão ativa, seus buffers e o vínculo com a sessão lógica do cliente.                                       |
| Session               | server        | Mantém identidade autenticada, estado transacional e contexto de execução associado a uma conexão.                           |
| RespConnectionHandler | server        | Coordena decodificação RESP, mapeamento de comando, despacho e codificação da resposta para uma conexão.                     |
| RespDecoder           | protocol.resp | Converte bytes RESP parciais ou completos em uma árvore de frames sem interpretar a semântica do comando.                    |
| RespEncoder           | protocol.resp | Converte resultados internos em respostas RESP válidas, incluindo erros e respostas em pipeline.                             |
| RespFrame             | protocol.resp | Modelo conceitual dos tipos RESP decodificados: arrays, strings, inteiros, nulos e erros.                                    |
| RespCommandMapper     | protocol.resp | Traduz um frame RESP de requisição para uma categoria de comando interno tipada.                                             |
| Command               | application   | Contrato comum das solicitações internas, independente do protocolo que as originou.                                         |
| AdminCommand          | application   | Categoria de comandos operacionais, incluindo PING, INFO e HEALTH.                                                           |
| CommandDispatcher     | application   | Aplica o pipeline comum e encaminha cada comando ao handler correspondente.                                                  |
| CommandHandler        | application   | Contrato dos handlers especializados que executam categorias de comandos internos.                                           |
| AdminCommandHandler   | application   | Executa PING e, posteriormente, consolida informações de saúde e métricas.                                                   |
| CommandValidator      | application   | Valida argumentos, limites, estado da sessão e regras operacionais antes da execução.                                        |
| CommandResult         | application   | Resultado neutro de execução, posteriormente adaptado para RESP ou SQL.                                                      |
| Authenticator         | security      | Resolve a identidade da sessão a partir das credenciais do protocolo; começa permissivo e é endurecido na etapa operacional. |
| AccessController      | security      | Autoriza comandos e escopos de chave para a identidade associada à sessão.                                                   |

## Classes alteradas nesta etapa

| **Classe**          | **Módulo**    | **Motivo da evolução**                                                                                     |
|---------------------|---------------|------------------------------------------------------------------------------------------------------------|
| TempoKvServer       | bootstrap     | Orquestra inicialização, recuperação, endpoints, workers e encerramento ordenado de uma instância TempoKV. |
| MetricsRegistry     | observability | Agrega contadores, latências, tamanhos e estados usados pelos comandos administrativos.                    |
| ServerHealthService | observability | Calcula estados STARTING, RECOVERING, READY, DEGRADED e STOPPING a partir dos subsistemas.                 |

## Fluxo validado

- redis-cli conecta, envia um ou vários PINGs na mesma conexão e recebe PONG.

- Frames fragmentados entre múltiplas leituras são remontados.

- Clientes lentos não bloqueiam outras conexões.

## Testes obrigatórios

- Golden tests de bytes RESP.

- Testes de frames fragmentados, concatenados e inválidos.

- Teste de centenas de conexões concorrentes.

- Teste de backpressure e desconexão durante escrita.

## Critérios de saída

- UC-02 passa usando redis-cli oficial.

- Event loop não contém lógica de comandos.

- Nenhum handler acessa sockets diretamente.

- Erros de protocolo são determinísticos.

## Entregáveis

- servidor RESP funcional

- testes de compatibilidade RESP

- documento do protocolo suportado

- benchmark inicial de conexões

# E3 — Comandos key-value e armazenamento versionado atual

| **Objetivo**              | Entregar SET/GET/DEL/EXPIRE/TTL sobre um storage MVCC, ainda priorizando o estado atual. |
|---------------------------|------------------------------------------------------------------------------------------|
| **Casos de uso cobertos** | UC-03                                                                                    |

## Descrição detalhada

- Introduzir KeyValueCommand e handler especializado.

- Definir StorageEngine como porta e MvccStore como implementação em memória.

- Construir KeyIndex, VersionChain e VersionedValue imutável.

- Implementar CommitCoordinator, VersionGenerator, Mutation e CommitRecord.

- Garantir que SET, DEL e mudança de TTL criem novas versões.

- Criar TtlIndex e semântica de expiração passiva em leitura.

- Manter o WAL como porta opcional/no-op até E5.

## Classes criadas nesta etapa

| **Classe**             | **Módulo**  | **Responsabilidade nesta etapa**                                                                                |
|------------------------|-------------|-----------------------------------------------------------------------------------------------------------------|
| KeyValueCommand        | application | Categoria de operações sobre o estado atual: GET, SET, DEL, EXPIRE e TTL.                                       |
| KeyValueCommandHandler | application | Executa leituras atuais e transforma mutações key-value em commits versionados.                                 |
| StorageEngine          | storage     | Porta de armazenamento usada pela aplicação para leituras atuais, históricas, aplicação de commits e snapshots. |
| MvccStore              | storage     | Implementação em memória do StorageEngine baseada em cadeias imutáveis de versões.                              |
| KeyIndex               | storage     | Índice primário que associa cada chave à sua cadeia de versões.                                                 |
| VersionChain           | storage     | Mantém as versões imutáveis de uma chave e resolve visibilidade por versão ou timestamp.                        |
| VersionedValue         | storage     | Representa uma versão imutável, com valor ou tombstone, timestamp, TTL e metadados de commit.                   |
| CommitCoordinator      | transaction | Coordena versão, WAL, aplicação atômica no storage e publicação para replicação.                                |
| VersionGenerator       | transaction | Gera identificadores de commit monotônicos dentro de um nó primário.                                            |
| Mutation               | transaction | Representa uma alteração imutável a ser confirmada: put, tombstone, TTL ou metadado.                            |
| CommitRecord           | transaction | Agrupa uma versão, metadados e uma lista ordenada de mutações atômicas.                                         |
| TtlIndex               | storage     | Índice ordenado dos próximos vencimentos, separado do índice principal de chaves.                               |

## Classes alteradas nesta etapa

| **Classe**        | **Módulo**    | **Motivo da evolução**                                                                  |
|-------------------|---------------|-----------------------------------------------------------------------------------------|
| RespCommandMapper | protocol.resp | Traduz um frame RESP de requisição para uma categoria de comando interno tipada.        |
| CommandDispatcher | application   | Aplica o pipeline comum e encaminha cada comando ao handler correspondente.             |
| CommandHandler    | application   | Contrato dos handlers especializados que executam categorias de comandos internos.      |
| CommandValidator  | application   | Valida argumentos, limites, estado da sessão e regras operacionais antes da execução.   |
| CommandResult     | application   | Resultado neutro de execução, posteriormente adaptado para RESP ou SQL.                 |
| AccessController  | security      | Autoriza comandos e escopos de chave para a identidade associada à sessão.              |
| MetricsRegistry   | observability | Agrega contadores, latências, tamanhos e estados usados pelos comandos administrativos. |

## Fluxo validado

- SET cria V1; GET lê V1; segundo SET cria V2; DEL cria tombstone V3.

- EXPIRE cria versão com novo prazo; TTL e GET respeitam o relógio.

- Pipelining mistura leitura e escrita preservando ordem de resposta.

## Testes obrigatórios

- Testes determinísticos com relógio controlado.

- Property tests de cadeia de versões e invariantes monotônicos.

- Testes concorrentes de leituras durante publicação de nova cabeça.

- Comparação funcional com Redis para comandos compatíveis.

## Critérios de saída

- UC-03 passa em memória.

- Todas as escritas passam pelo CommitCoordinator.

- StorageEngine não depende de RESP ou SQL.

- Tombstones preservam histórico interno.

## Entregáveis

- núcleo MVCC atual

- comandos key-value

- testes de invariantes

- benchmark GET/SET em memória

# E4 — Operações temporais, restauração e retenção

| **Objetivo**              | Expor o diferencial do produto: leitura histórica, histórico, diff e restauração append-only. |
|---------------------------|-----------------------------------------------------------------------------------------------|
| **Casos de uso cobertos** | UC-05, UC-06, UC-07                                                                           |

## Descrição detalhada

- Adicionar TemporalCommand e TemporalCommandHandler.

- Estender VersionChain para busca por versão e timestamp.

- Implementar GETAT, HISTORY, DIFF e RESTOREAT na interface RESP.

- Definir RetentionPolicy por padrão e por prefixo.

- Implementar HistoryGarbageCollector respeitando snapshots ativos futuros.

- Distinguir chave inexistente, tombstone e histórico removido.

## Classes criadas nesta etapa

| **Classe**              | **Módulo**  | **Responsabilidade nesta etapa**                                                                 |
|-------------------------|-------------|--------------------------------------------------------------------------------------------------|
| TemporalCommand         | application | Categoria de operações históricas: GETAT, HISTORY, DIFF e RESTOREAT.                             |
| TemporalCommandHandler  | application | Executa consultas históricas, comparações e restauração por meio do storage MVCC.                |
| RetentionPolicy         | storage     | Define quantas versões e por quanto tempo o histórico deve ser preservado por padrão ou prefixo. |
| HistoryGarbageCollector | storage     | Remove versões fora da retenção sem invalidar snapshots ativos.                                  |

## Classes alteradas nesta etapa

| **Classe**        | **Módulo**    | **Motivo da evolução**                                                                        |
|-------------------|---------------|-----------------------------------------------------------------------------------------------|
| RespCommandMapper | protocol.resp | Traduz um frame RESP de requisição para uma categoria de comando interno tipada.              |
| CommandDispatcher | application   | Aplica o pipeline comum e encaminha cada comando ao handler correspondente.                   |
| CommandValidator  | application   | Valida argumentos, limites, estado da sessão e regras operacionais antes da execução.         |
| CommandResult     | application   | Resultado neutro de execução, posteriormente adaptado para RESP ou SQL.                       |
| MvccStore         | storage       | Implementação em memória do StorageEngine baseada em cadeias imutáveis de versões.            |
| VersionChain      | storage       | Mantém as versões imutáveis de uma chave e resolve visibilidade por versão ou timestamp.      |
| VersionedValue    | storage       | Representa uma versão imutável, com valor ou tombstone, timestamp, TTL e metadados de commit. |
| CommitCoordinator | transaction   | Coordena versão, WAL, aplicação atômica no storage e publicação para replicação.              |
| Mutation          | transaction   | Representa uma alteração imutável a ser confirmada: put, tombstone, TTL ou metadado.          |
| MetricsRegistry   | observability | Agrega contadores, latências, tamanhos e estados usados pelos comandos administrativos.       |

## Fluxo validado

- GETAT resolve versões e timestamps anteriores.

- HISTORY pagina versões e DIFF compara dois pontos.

- RESTOREAT cria nova versão sem remover as posteriores.

- GC remove apenas versões fora da política e não referenciadas.

## Testes obrigatórios

- Testes de fronteira temporal e timestamps iguais.

- Testes de tombstone e recriação de chave.

- Property tests de restauração e imutabilidade do histórico.

- Testes de retenção por quantidade, idade e prefixo.

## Critérios de saída

- UC-05, UC-06 e UC-07 passam em memória.

- Nenhuma operação histórica altera o estado, exceto RESTOREAT por novo commit.

- A política de retenção é explícita e observável.

- A demo principal do diferencial já é executável.

## Entregáveis

- API temporal RESP

- políticas de retenção

- demo de time travel

- benchmark GETAT por profundidade de cadeia

# E5 — Durabilidade, recuperação, expiração ativa e compactação

| **Objetivo**              | Tornar commits recuperáveis após falha e limitar crescimento dos arquivos e do histórico. |
|---------------------------|-------------------------------------------------------------------------------------------|
| **Casos de uso cobertos** | UC-01, UC-10, UC-11, UC-03, UC-07, UC-08                                                  |

## Descrição detalhada

- Implementar WAL binário segmentado com codec, checksum, versionamento de formato e política de fsync.

- Alterar CommitCoordinator para registrar antes de publicar.

- Implementar snapshot consistente, armazenamento atômico e replay.

- Implementar RecoveryManager antes da abertura dos endpoints.

- Ativar ExpirationWorker com tombstones duráveis.

- Implementar compactação do WAL e integração inicial com HistoryGarbageCollector.

- Adicionar injeção de falhas pelo FileSystemAdapter.

## Classes criadas nesta etapa

| **Classe**        | **Módulo**  | **Responsabilidade nesta etapa**                                                            |
|-------------------|-------------|---------------------------------------------------------------------------------------------|
| ExpirationWorker  | storage     | Consome vencimentos do TtlIndex e solicita tombstones versionados ao CommitCoordinator.     |
| WriteAheadLog     | persistence | Porta de append, sync e replay de commits duráveis.                                         |
| FileWriteAheadLog | persistence | Implementação segmentada em arquivos do WriteAheadLog.                                      |
| WalRecordCodec    | persistence | Codifica e decodifica registros binários autodelimitados do WAL.                            |
| FsyncPolicy       | persistence | Define quando commits gravados são sincronizados no dispositivo.                            |
| SnapshotStore     | persistence | Persiste e carrega snapshots validados e seus metadados de versão.                          |
| SnapshotWriter    | persistence | Captura um StorageSnapshot consistente e o publica de forma atômica.                        |
| WalCompactor      | persistence | Descarta segmentos do WAL cobertos por snapshot durável e pelas necessidades de replicação. |
| RecoveryManager   | persistence | Reconstrói o storage carregando snapshot e reaplicando commits válidos do WAL.              |
| StorageSnapshot   | storage     | Representação consistente e serializável do estado retido em uma versão de corte.           |

## Classes alteradas nesta etapa

| **Classe**              | **Módulo**    | **Motivo da evolução**                                                                                                    |
|-------------------------|---------------|---------------------------------------------------------------------------------------------------------------------------|
| TempoKvServer           | bootstrap     | Orquestra inicialização, recuperação, endpoints, workers e encerramento ordenado de uma instância TempoKV.                |
| ServerConfiguration     | bootstrap     | Modelo imutável das opções do nó: portas, diretório de dados, papel primário/réplica, retenção, persistência e segurança. |
| CommitCoordinator       | transaction   | Coordena versão, WAL, aplicação atômica no storage e publicação para replicação.                                          |
| CommitRecord            | transaction   | Agrupa uma versão, metadados e uma lista ordenada de mutações atômicas.                                                   |
| Mutation                | transaction   | Representa uma alteração imutável a ser confirmada: put, tombstone, TTL ou metadado.                                      |
| StorageEngine           | storage       | Porta de armazenamento usada pela aplicação para leituras atuais, históricas, aplicação de commits e snapshots.           |
| MvccStore               | storage       | Implementação em memória do StorageEngine baseada em cadeias imutáveis de versões.                                        |
| KeyIndex                | storage       | Índice primário que associa cada chave à sua cadeia de versões.                                                           |
| VersionChain            | storage       | Mantém as versões imutáveis de uma chave e resolve visibilidade por versão ou timestamp.                                  |
| VersionedValue          | storage       | Representa uma versão imutável, com valor ou tombstone, timestamp, TTL e metadados de commit.                             |
| TtlIndex                | storage       | Índice ordenado dos próximos vencimentos, separado do índice principal de chaves.                                         |
| RetentionPolicy         | storage       | Define quantas versões e por quanto tempo o histórico deve ser preservado por padrão ou prefixo.                          |
| HistoryGarbageCollector | storage       | Remove versões fora da retenção sem invalidar snapshots ativos.                                                           |
| FileSystemAdapter       | persistence   | Centraliza operações de arquivo, rename atômico, locks, sync e simulação de falhas em testes.                             |
| ServerHealthService     | observability | Calcula estados STARTING, RECOVERING, READY, DEGRADED e STOPPING a partir dos subsistemas.                                |
| MetricsRegistry         | observability | Agrega contadores, latências, tamanhos e estados usados pelos comandos administrativos.                                   |

## Fluxo validado

- Após restart, snapshot + WAL reconstruem estado atual, histórico e TTL.

- Falha após append e antes da publicação não perde commit durável.

- Cauda incompleta do WAL é ignorada; corrupção interna bloqueia startup.

- Expiração cria tombstone recuperável.

- Snapshot reduz replay e compactação preserva dados necessários.

## Testes obrigatórios

- Matriz de crash points em cada etapa do commit.

- Testes de checksum, truncamento e versão de formato.

- Testes de snapshot atômico com falha antes/depois de rename.

- Teste de recuperação com milhões de registros sintéticos.

- Teste de expiração durante shutdown/restart.

## Critérios de saída

- UC-01, UC-10 e UC-11 passam.

- UC-03 e UC-07 tornam-se duráveis.

- Tempo de recuperação é medido e documentado.

- Nenhum commit é respondido como confirmado antes da política de durabilidade.

## Entregáveis

- formato WAL documentado

- snapshots e compactação

- suite de crash recovery

- benchmarks de fsync e replay

# E6 — Front-end SQL com JFlex e Java CUP

| **Objetivo**              | Adicionar uma interface SQL limitada que compile para os mesmos comandos e planos internos. |
|---------------------------|---------------------------------------------------------------------------------------------|
| **Casos de uso cobertos** | UC-04, UC-05, UC-06, UC-07                                                                  |

## Descrição detalhada

- Definir gramática mínima: SELECT, UPSERT, DELETE, AS OF, HISTORY, DIFF, BEGIN, COMMIT e ROLLBACK.

- Substituir/completar o tokenizer atual por especificação JFlex com erros posicionais.

- Gerar parser Java CUP e uma AST imutável baseada em Statement/Expression.

- Implementar análise semântica e planner sem JOIN nem otimizador de custo.

- Implementar PlanExecutor que reutiliza CommandDispatcher e handlers.

- Criar endpoint SQL textual e encoder tabular/JSON.

- Garantir equivalência semântica entre RESP e SQL.

## Classes criadas nesta etapa

| **Classe**           | **Módulo**   | **Responsabilidade nesta etapa**                                                                               |
|----------------------|--------------|----------------------------------------------------------------------------------------------------------------|
| SqlServer            | server       | Endpoint TCP textual dedicado ao SQL temporal e administrativo.                                                |
| SqlConnectionHandler | server       | Coordena compilação SQL, execução do plano e serialização tabular da resposta.                                 |
| TempoLexer           | protocol.sql | Lexer gerado por JFlex. Converte texto SQL em tokens com posição, linha e coluna.                              |
| TempoParser          | protocol.sql | Parser gerado por Java CUP. Constrói a AST a partir dos tokens emitidos pelo lexer.                            |
| Statement            | protocol.sql | Raiz conceitual das instruções SQL aceitas pelo produto, como SELECT, UPSERT e controle transacional.          |
| Expression           | protocol.sql | Raiz das expressões da AST: literais, referências, predicados e funções temporais.                             |
| SqlSemanticAnalyzer  | protocol.sql | Valida nomes, tipos, funções, cláusulas temporais e permissões sem executar a consulta.                        |
| SqlPlanner           | protocol.sql | Transforma uma AST validada em um plano lógico orientado às capacidades key-value e temporais do storage.      |
| ExecutionPlan        | protocol.sql | Representa operações lógicas como point lookup, historical lookup, filter, projection, sort, limit e mutation. |
| PlanExecutor         | protocol.sql | Executa o plano SQL usando o mesmo dispatcher e os mesmos handlers utilizados pela interface RESP.             |
| SqlResultEncoder     | protocol.sql | Serializa resultados internos em formato tabular textual e, opcionalmente, JSON administrativo.                |

## Classes alteradas nesta etapa

| **Classe**             | **Módulo**    | **Motivo da evolução**                                                                                      |
|------------------------|---------------|-------------------------------------------------------------------------------------------------------------|
| TempoKvServer          | bootstrap     | Orquestra inicialização, recuperação, endpoints, workers e encerramento ordenado de uma instância TempoKV.  |
| NioEventLoop           | server        | Gerencia sockets não bloqueantes, leitura parcial, escrita pendente e backpressure para múltiplos clientes. |
| ClientConnection       | server        | Representa uma conexão ativa, seus buffers e o vínculo com a sessão lógica do cliente.                      |
| Session                | server        | Mantém identidade autenticada, estado transacional e contexto de execução associado a uma conexão.          |
| Command                | application   | Contrato comum das solicitações internas, independente do protocolo que as originou.                        |
| CommandDispatcher      | application   | Aplica o pipeline comum e encaminha cada comando ao handler correspondente.                                 |
| CommandResult          | application   | Resultado neutro de execução, posteriormente adaptado para RESP ou SQL.                                     |
| KeyValueCommandHandler | application   | Executa leituras atuais e transforma mutações key-value em commits versionados.                             |
| TemporalCommandHandler | application   | Executa consultas históricas, comparações e restauração por meio do storage MVCC.                           |
| MetricsRegistry        | observability | Agrega contadores, latências, tamanhos e estados usados pelos comandos administrativos.                     |

## Fluxo validado

- UPSERT SQL e SET RESP produzem o mesmo CommitRecord.

- SELECT atual e AS OF usam StorageEngine sem caminho paralelo.

- HISTORY SQL aplica projection/filter/limit sobre resultado temporal.

- Erros léxicos, sintáticos e semânticos são distintos.

## Testes obrigatórios

- Golden tests de tokens e AST.

- Testes de gramática válidos/inválidos.

- Testes de equivalência SQL versus RESP.

- Fuzzing de lexer/parser com limites de tamanho.

- Testes de plano para impedir full scans não suportados.

## Critérios de saída

- UC-04 passa e os caminhos SQL de UC-05/06/07 passam.

- JFlex e CUP são executados pelo build; código gerado não é editado manualmente.

- SQL não acessa mapas ou arquivos diretamente.

- Escopo da linguagem está documentado.

## Entregáveis

- gramática SQL

- geração JFlex/CUP integrada

- CLI SQL

- documentação da linguagem

- demo SQL temporal

# E7 — Transações, segurança e observabilidade operacional

| **Objetivo**              | Completar consistência concorrente, ACL e ferramentas necessárias para operar e demonstrar o banco. |
|---------------------------|-----------------------------------------------------------------------------------------------------|
| **Casos de uso cobertos** | UC-08, UC-09, UC-12, UC-03, UC-04, UC-05, UC-06, UC-07                                              |

## Descrição detalhada

- Implementar BEGIN/COMMIT/ROLLBACK com TransactionContext por Session.

- Implementar SnapshotManager, write set e ConflictDetector por conflito write-write.

- Garantir commit atômico de múltiplas mutações.

- Endurecer Authenticator e AccessController com usuários, comandos e prefixos de chave.

- Adicionar CommandTracer e ampliar MetricsRegistry/ServerHealthService.

- Completar INFO e HEALTH por AdminCommandHandler.

- Validar GC com snapshots ativos.

## Classes criadas nesta etapa

| **Classe**                | **Módulo**    | **Responsabilidade nesta etapa**                                                        |
|---------------------------|---------------|-----------------------------------------------------------------------------------------|
| TransactionCommand        | application   | Categoria de controle transacional: BEGIN, COMMIT e ROLLBACK.                           |
| TransactionCommandHandler | application   | Conecta os comandos transacionais ao TransactionManager e ao contexto da sessão.        |
| TransactionManager        | transaction   | Controla abertura, commit, rollback e transição de estado das transações de uma sessão. |
| TransactionContext        | transaction   | Mantém snapshot, write set e estado de uma transação ativa.                             |
| SnapshotManager           | transaction   | Registra snapshots ativos e define a versão máxima visível para leituras consistentes.  |
| ConflictDetector          | transaction   | Detecta conflitos de escrita comparando versões atuais com a versão do snapshot.        |
| CommandTracer             | observability | Registra o caminho e os tempos de cada comando sem expor valores sensíveis.             |

## Classes alteradas nesta etapa

| **Classe**              | **Módulo**    | **Motivo da evolução**                                                                                                       |
|-------------------------|---------------|------------------------------------------------------------------------------------------------------------------------------|
| Session                 | server        | Mantém identidade autenticada, estado transacional e contexto de execução associado a uma conexão.                           |
| Authenticator           | security      | Resolve a identidade da sessão a partir das credenciais do protocolo; começa permissivo e é endurecido na etapa operacional. |
| AccessController        | security      | Autoriza comandos e escopos de chave para a identidade associada à sessão.                                                   |
| AdminCommand            | application   | Categoria de comandos operacionais, incluindo PING, INFO e HEALTH.                                                           |
| AdminCommandHandler     | application   | Executa PING e, posteriormente, consolida informações de saúde e métricas.                                                   |
| CommandDispatcher       | application   | Aplica o pipeline comum e encaminha cada comando ao handler correspondente.                                                  |
| CommandValidator        | application   | Valida argumentos, limites, estado da sessão e regras operacionais antes da execução.                                        |
| CommandResult           | application   | Resultado neutro de execução, posteriormente adaptado para RESP ou SQL.                                                      |
| KeyValueCommandHandler  | application   | Executa leituras atuais e transforma mutações key-value em commits versionados.                                              |
| TemporalCommandHandler  | application   | Executa consultas históricas, comparações e restauração por meio do storage MVCC.                                            |
| PlanExecutor            | protocol.sql  | Executa o plano SQL usando o mesmo dispatcher e os mesmos handlers utilizados pela interface RESP.                           |
| CommitCoordinator       | transaction   | Coordena versão, WAL, aplicação atômica no storage e publicação para replicação.                                             |
| StorageEngine           | storage       | Porta de armazenamento usada pela aplicação para leituras atuais, históricas, aplicação de commits e snapshots.              |
| HistoryGarbageCollector | storage       | Remove versões fora da retenção sem invalidar snapshots ativos.                                                              |
| MetricsRegistry         | observability | Agrega contadores, latências, tamanhos e estados usados pelos comandos administrativos.                                      |
| ServerHealthService     | observability | Calcula estados STARTING, RECOVERING, READY, DEGRADED e STOPPING a partir dos subsistemas.                                   |
| RespConnectionHandler   | server        | Coordena decodificação RESP, mapeamento de comando, despacho e codificação da resposta para uma conexão.                     |
| SqlConnectionHandler    | server        | Coordena compilação SQL, execução do plano e serialização tabular da resposta.                                               |

## Fluxo validado

- Transação lê snapshot estável e publica um único commit.

- Conflito write-write é detectado antes do WAL.

- ROLLBACK não gera versão.

- Usuários sem permissão recebem erro consistente em RESP e SQL.

- INFO/HEALTH expõem latência, storage, WAL, snapshots e conflitos.

## Testes obrigatórios

- Testes concorrentes com barreiras determinísticas.

- Lincheck ou JCStress nas estruturas críticas.

- Testes de ACL por comando e prefixo.

- Testes de não vazamento de valores no tracing.

- Testes de GC enquanto snapshots permanecem ativos.

## Critérios de saída

- UC-08, UC-09 e UC-12 passam.

- Todos os casos anteriores passam sob ACL e tracing.

- Métricas p50/p95/p99 são coletadas.

- Não há escrita direta em réplica ou fora do CommitCoordinator.

## Entregáveis

- transações MVCC

- ACL

- INFO/HEALTH

- painel textual de métricas

- relatório de concorrência

# E8 — Replicação, hardening e publicação de portfólio

| **Objetivo**              | Entregar dois papéis de nó, sincronização reproduzível e uma apresentação profissional do projeto. |
|---------------------------|----------------------------------------------------------------------------------------------------|
| **Casos de uso cobertos** | UC-13, UC-00, UC-01, UC-11, UC-12                                                                  |

## Descrição detalhada

- Implementar handshake, estado de réplica e endpoint do primário.

- Escolher sincronização incremental por WAL ou completa por snapshot.

- Aplicar commits em réplica sem gerar versões locais.

- Rastrear ACKs e integrar compactação com réplicas atrasadas.

- Impor modo read-only nos endpoints da réplica.

- Criar docker-compose primário + réplica e testes end-to-end.

- Executar profiling, benchmark reproduzível, auditoria de falhas e revisão de API.

- Finalizar README, ADRs, diagramas, exemplos, GIF/vídeo curto e release versionada.

## Classes criadas nesta etapa

| **Classe**                 | **Módulo**  | **Responsabilidade nesta etapa**                                                         |
|----------------------------|-------------|------------------------------------------------------------------------------------------|
| ReplicationManager         | replication | Orquestra o modo primário ou réplica e integra commits, sincronização e ACKs.            |
| PrimaryReplicationEndpoint | replication | Aceita réplicas, autentica o handshake e transmite snapshot ou commits incrementais.     |
| ReplicaClient              | replication | Mantém a conexão de uma réplica com o primário e recebe o fluxo ordenado.                |
| ReplicaApplier             | replication | Valida e aplica localmente snapshots e CommitRecords recebidos, sem gerar novas versões. |
| ReplicaState               | replication | Mantém papel, versão aplicada, versão confirmada e estado de sincronização do nó.        |
| AckTracker                 | replication | Rastreia a versão confirmada por cada réplica e suporta decisões de compactação.         |
| SyncCoordinator            | replication | Escolhe entre sincronização completa por snapshot e sincronização incremental por WAL.   |

## Classes alteradas nesta etapa

| **Classe**          | **Módulo**    | **Motivo da evolução**                                                                                                    |
|---------------------|---------------|---------------------------------------------------------------------------------------------------------------------------|
| TempoKvApplication  | bootstrap     | Ponto de entrada do processo. Carrega configuração, monta o grafo de componentes e inicia o ciclo de vida do nó.          |
| ServerConfiguration | bootstrap     | Modelo imutável das opções do nó: portas, diretório de dados, papel primário/réplica, retenção, persistência e segurança. |
| TempoKvServer       | bootstrap     | Orquestra inicialização, recuperação, endpoints, workers e encerramento ordenado de uma instância TempoKV.                |
| RespServer          | server        | Endpoint TCP compatível com RESP. Aceita conexões Redis e delega cada sessão ao handler apropriado.                       |
| SqlServer           | server        | Endpoint TCP textual dedicado ao SQL temporal e administrativo.                                                           |
| NioEventLoop        | server        | Gerencia sockets não bloqueantes, leitura parcial, escrita pendente e backpressure para múltiplos clientes.               |
| CommitCoordinator   | transaction   | Coordena versão, WAL, aplicação atômica no storage e publicação para replicação.                                          |
| VersionGenerator    | transaction   | Gera identificadores de commit monotônicos dentro de um nó primário.                                                      |
| CommitRecord        | transaction   | Agrupa uma versão, metadados e uma lista ordenada de mutações atômicas.                                                   |
| StorageEngine       | storage       | Porta de armazenamento usada pela aplicação para leituras atuais, históricas, aplicação de commits e snapshots.           |
| WriteAheadLog       | persistence   | Porta de append, sync e replay de commits duráveis.                                                                       |
| FileWriteAheadLog   | persistence   | Implementação segmentada em arquivos do WriteAheadLog.                                                                    |
| WalRecordCodec      | persistence   | Codifica e decodifica registros binários autodelimitados do WAL.                                                          |
| SnapshotStore       | persistence   | Persiste e carrega snapshots validados e seus metadados de versão.                                                        |
| WalCompactor        | persistence   | Descarta segmentos do WAL cobertos por snapshot durável e pelas necessidades de replicação.                               |
| RecoveryManager     | persistence   | Reconstrói o storage carregando snapshot e reaplicando commits válidos do WAL.                                            |
| AccessController    | security      | Autoriza comandos e escopos de chave para a identidade associada à sessão.                                                |
| MetricsRegistry     | observability | Agrega contadores, latências, tamanhos e estados usados pelos comandos administrativos.                                   |
| CommandTracer       | observability | Registra o caminho e os tempos de cada comando sem expor valores sensíveis.                                               |
| ServerHealthService | observability | Calcula estados STARTING, RECOVERING, READY, DEGRADED e STOPPING a partir dos subsistemas.                                |
| FileSystemAdapter   | persistence   | Centraliza operações de arquivo, rename atômico, locks, sync e simulação de falhas em testes.                             |

## Fluxo validado

- Réplica vazia recebe snapshot e alcança o primário.

- Réplica desconectada retoma por WAL quando o intervalo existe.

- Commit fora de ordem é rejeitado.

- Leituras na réplica retornam estado aplicado; escritas retornam READONLY.

- Compactação respeita ACKs ou força full resync.

- Stack completa inicia por Docker e executa todos os casos de uso.

## Testes obrigatórios

- Testes end-to-end com kill/restart de primário e réplica.

- Testes de reconnect, atraso e perda de conexão.

- Teste de invariância de versão entre primário e réplica.

- Benchmarks JMH e carga por protocolo.

- Análise de memória, p95/p99 e custo de histórico.

- Build limpo em CI e scanner de dependências.

## Critérios de saída

- UC-13 passa e todos os UCs anteriores têm regressão verde.

- Docker Compose demonstra primário/réplica em um comando.

- Resultados de benchmark são reproduzíveis e não fazem alegações sem método.

- Repositório contém licença, arquitetura, decisões, roadmap e demonstração.

- Release candidata pronta para ser fixada no perfil GitHub.

## Entregáveis

- replicação primário-réplica

- Docker Compose completo

- suite end-to-end

- relatório de benchmarks

- README de portfólio

- release 1.0.0

# Checklist final para portfólio

- README abre com proposta, diferencial temporal, arquitetura e demonstração de 30 segundos.

- Docker Compose sobe primário e réplica com volume persistente.

- redis-cli executa PING, SET, GET, GETAT, HISTORY, DIFF e RESTOREAT.

- CLI SQL executa SELECT atual, AS OF, HISTORY e transações.

- Restart demonstra recuperação por snapshot + WAL.

- Teste concorrente demonstra snapshot consistente e conflito write-write.

- INFO/HEALTH mostra papel, versão, WAL, snapshot, réplica, latência e memória estimada.

- Benchmarks documentam ambiente, carga, p50/p95/p99 e trade-offs de fsync/MVCC.

- ADRs justificam Java NIO, JFlex/CUP, WAL, MVCC, single-node-first e protocolo de replicação.

- CI executa testes unitários, integração, compatibilidade, crash recovery e Docker smoke test.

- Release 1.0.0 inclui licença, changelog, artefatos e vídeo/GIF curto.

# Matriz final de rastreabilidade

| **Etapa** | **Objetivo**                                             | **Fluxos**                                             | **Cria**                                                                                                                                                                                                                                                                            | **Altera**                                                                                                                                                                                                                                                                                                                                              |
|-----------|----------------------------------------------------------|--------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| E1        | Fundação, configuração e ciclo de vida                   | UC-00                                                  | TempoKvApplication, ServerConfiguration, TempoKvServer, FileSystemAdapter, DatabaseLock, MetricsRegistry, ServerHealthService                                                                                                                                                       | —                                                                                                                                                                                                                                                                                                                                                       |
| E2        | Servidor NIO, RESP e pipeline administrativo             | UC-02                                                  | RespServer, NioEventLoop, ClientConnection, Session, RespConnectionHandler, RespDecoder, RespEncoder, RespFrame, RespCommandMapper, Command, AdminCommand, CommandDispatcher, CommandHandler, AdminCommandHandler, CommandValidator, CommandResult, Authenticator, AccessController | TempoKvServer, MetricsRegistry, ServerHealthService                                                                                                                                                                                                                                                                                                     |
| E3        | Comandos key-value e armazenamento versionado atual      | UC-03                                                  | KeyValueCommand, KeyValueCommandHandler, StorageEngine, MvccStore, KeyIndex, VersionChain, VersionedValue, CommitCoordinator, VersionGenerator, Mutation, CommitRecord, TtlIndex                                                                                                    | RespCommandMapper, CommandDispatcher, CommandHandler, CommandValidator, CommandResult, AccessController, MetricsRegistry                                                                                                                                                                                                                                |
| E4        | Operações temporais, restauração e retenção              | UC-05, UC-06, UC-07                                    | TemporalCommand, TemporalCommandHandler, RetentionPolicy, HistoryGarbageCollector                                                                                                                                                                                                   | RespCommandMapper, CommandDispatcher, CommandValidator, CommandResult, MvccStore, VersionChain, VersionedValue, CommitCoordinator, Mutation, MetricsRegistry                                                                                                                                                                                            |
| E5        | Durabilidade, recuperação, expiração ativa e compactação | UC-01, UC-10, UC-11, UC-03, UC-07, UC-08               | ExpirationWorker, WriteAheadLog, FileWriteAheadLog, WalRecordCodec, FsyncPolicy, SnapshotStore, SnapshotWriter, WalCompactor, RecoveryManager, StorageSnapshot                                                                                                                      | TempoKvServer, ServerConfiguration, CommitCoordinator, CommitRecord, Mutation, StorageEngine, MvccStore, KeyIndex, VersionChain, VersionedValue, TtlIndex, RetentionPolicy, HistoryGarbageCollector, FileSystemAdapter, ServerHealthService, MetricsRegistry                                                                                            |
| E6        | Front-end SQL com JFlex e Java CUP                       | UC-04, UC-05, UC-06, UC-07                             | SqlServer, SqlConnectionHandler, TempoLexer, TempoParser, Statement, Expression, SqlSemanticAnalyzer, SqlPlanner, ExecutionPlan, PlanExecutor, SqlResultEncoder                                                                                                                     | TempoKvServer, NioEventLoop, ClientConnection, Session, Command, CommandDispatcher, CommandResult, KeyValueCommandHandler, TemporalCommandHandler, MetricsRegistry                                                                                                                                                                                      |
| E7        | Transações, segurança e observabilidade operacional      | UC-08, UC-09, UC-12, UC-03, UC-04, UC-05, UC-06, UC-07 | TransactionCommand, TransactionCommandHandler, TransactionManager, TransactionContext, SnapshotManager, ConflictDetector, CommandTracer                                                                                                                                             | Session, Authenticator, AccessController, AdminCommand, AdminCommandHandler, CommandDispatcher, CommandValidator, CommandResult, KeyValueCommandHandler, TemporalCommandHandler, PlanExecutor, CommitCoordinator, StorageEngine, HistoryGarbageCollector, MetricsRegistry, ServerHealthService, RespConnectionHandler, SqlConnectionHandler             |
| E8        | Replicação, hardening e publicação de portfólio          | UC-13, UC-00, UC-01, UC-11, UC-12                      | ReplicationManager, PrimaryReplicationEndpoint, ReplicaClient, ReplicaApplier, ReplicaState, AckTracker, SyncCoordinator                                                                                                                                                            | TempoKvApplication, ServerConfiguration, TempoKvServer, RespServer, SqlServer, NioEventLoop, CommitCoordinator, VersionGenerator, CommitRecord, StorageEngine, WriteAheadLog, FileWriteAheadLog, WalRecordCodec, SnapshotStore, WalCompactor, RecoveryManager, AccessController, MetricsRegistry, CommandTracer, ServerHealthService, FileSystemAdapter |

Regra de consistência: cada fluxo citado neste plano possui um caso de uso detalhado; cada caso de uso aparece em ao menos uma etapa; toda classe criada ou alterada pertence ao diagrama conceitual.
