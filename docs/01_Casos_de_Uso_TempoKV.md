# TempoKV — Casos de Uso e Fluxos Completos

> Fluxos de produto vinculados diretamente às classes da arquitetura.

| Arquitetura-alvo: servidor single-node em Java 25, distribuído por JAR e Docker, com Java NIO, storage MVCC, WAL, SQL via JFlex/CUP e replicação primário-réplica. |
|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|

Versão arquitetural 1.0 • Documento consistente com os demais artefatos da série

# 1. Escopo e convenções

Este documento descreve os fluxos funcionais que definem o produto. Os nomes entre colchetes são classes exatas do diagrama conceitual; os identificadores E1–E8 apontam para o plano de implementação.

> Nota de leitura
> RESP e SQL são front-ends diferentes. Depois do mapeamento ou planejamento, ambos usam CommandDispatcher, handlers, CommitCoordinator e StorageEngine. Nenhum caso de uso cria um storage paralelo para SQL.

# 2. Catálogo resumido

| **ID** | **Caso de uso**                                              | **Ator principal**                                  | **Etapa** |
|--------|--------------------------------------------------------------|-----------------------------------------------------|-----------|
| UC-00  | Iniciar uma instância local ou por Docker                    | Operador / plataforma de containers                 | E1        |
| UC-01  | Recuperar o estado após reinício ou falha                    | TempoKvServer / operador                            | E5        |
| UC-02  | Conectar por RESP e executar PING                            | Cliente Redis                                       | E2        |
| UC-03  | Gravar, consultar e excluir o valor atual com TTL            | Aplicação cliente Redis                             | E3        |
| UC-04  | Executar consulta ou mutação pela interface SQL              | Operador, desenvolvedor ou aplicação administrativa | E6        |
| UC-05  | Consultar valor em uma versão ou instante histórico          | Cliente Redis ou SQL                                | E4        |
| UC-06  | Inspecionar histórico e comparar versões                     | Auditor ou operador                                 | E4        |
| UC-07  | Restaurar uma versão histórica                               | Operador autorizado                                 | E4        |
| UC-08  | Executar transação com snapshot consistente                  | Aplicação cliente                                   | E7        |
| UC-09  | Detectar e abortar conflito concorrente                      | Duas ou mais aplicações clientes                    | E7        |
| UC-10  | Expirar chave automaticamente preservando o evento histórico | TempoKvServer / aplicação cliente                   | E5        |
| UC-11  | Criar snapshot e compactar o WAL                             | TempoKvServer / operador                            | E5        |
| UC-12  | Consultar saúde, métricas e informações administrativas      | Operador, monitor ou cliente autorizado             | E7        |
| UC-13  | Replicar commits para uma réplica e servir leitura read-only | Nó primário, nó réplica e aplicação leitora         | E8        |

# UC-00 — Iniciar uma instância local ou por Docker

| **Objetivo**       | Disponibilizar um nó TempoKV configurado, com diretório de dados exclusivo e estado de saúde observável. |
|--------------------|----------------------------------------------------------------------------------------------------------|
| **Atores**         | Operador / plataforma de containers                                                                      |
| **Gatilho**        | O operador executa o JAR ou inicia o container.                                                          |
| **Etapa primária** | E1                                                                                                       |
| **Validação**      | E1 valida inicialização vazia; E5 adiciona recuperação; E8 valida Docker e papéis primário/réplica.      |

## Pré-condições

- JDK ou imagem Docker disponível.

- Portas e diretório de dados definidos.

## Fluxo principal

**1.** TempoKvApplication carrega e valida ServerConfiguration. [TempoKvApplication, ServerConfiguration]

**2.** DatabaseLock solicita exclusividade sobre o diretório por meio de FileSystemAdapter. [DatabaseLock, FileSystemAdapter]

**3.** TempoKvApplication constrói TempoKvServer com os componentes habilitados. [TempoKvApplication, TempoKvServer]

**4.** ServerHealthService marca o nó como STARTING; MetricsRegistry inicia os indicadores básicos. [ServerHealthService, MetricsRegistry]

**5.** TempoKvServer invoca RecoveryManager quando existe estado persistido; o detalhamento está em UC-01. [TempoKvServer, RecoveryManager]

**6.** TempoKvServer inicia RespServer, SqlServer e os workers habilitados pela configuração. [TempoKvServer, RespServer, SqlServer, ExpirationWorker, HistoryGarbageCollector, ReplicationManager]

**7.** Após os endpoints estarem aceitando conexões, ServerHealthService publica READY. [ServerHealthService, RespServer, SqlServer]

## Fluxos alternativos e falhas

| **Condição**           | **Comportamento**                                                          | **Componentes**                         |
|------------------------|----------------------------------------------------------------------------|-----------------------------------------|
| Configuração inválida  | A aplicação encerra antes de abrir portas e apresenta os campos inválidos. | ServerConfiguration, TempoKvApplication |
| Diretório já bloqueado | DatabaseLock impede uma segunda instância sobre os mesmos arquivos.        | DatabaseLock, FileSystemAdapter         |
| Recuperação falha      | O nó permanece DEGRADED e não aceita escritas.                             | RecoveryManager, ServerHealthService    |

## Pós-condições

- Existe no máximo uma instância escritora por diretório.

- Os endpoints ativos correspondem à configuração.

- O estado de saúde é consultável.

## Componentes participantes

TempoKvApplication, ServerConfiguration, TempoKvServer, FileSystemAdapter, DatabaseLock, RecoveryManager, RespServer, SqlServer, ExpirationWorker, HistoryGarbageCollector, ReplicationManager, MetricsRegistry, ServerHealthService

# UC-01 — Recuperar o estado após reinício ou falha

| **Objetivo**       | Reconstruir exatamente o último estado durável antes de aceitar tráfego. |
|--------------------|--------------------------------------------------------------------------|
| **Atores**         | TempoKvServer / operador                                                 |
| **Gatilho**        | TempoKvServer inicia e detecta dados persistidos.                        |
| **Etapa primária** | E5                                                                       |
| **Validação**      | E5; regressão adicional em E8 com estado de réplica.                     |

## Pré-condições

- DatabaseLock adquirido.

- Diretório de dados contém zero ou mais snapshots e segmentos de WAL.

## Fluxo principal

**1.** ServerHealthService muda para RECOVERING. [ServerHealthService]

**2.** RecoveryManager solicita ao SnapshotStore o snapshot válido mais recente. [RecoveryManager, SnapshotStore]

**3.** SnapshotStore usa FileSystemAdapter para ler e validar o artefato. [SnapshotStore, FileSystemAdapter]

**4.** RecoveryManager restaura StorageSnapshot em StorageEngine/MvccStore. [RecoveryManager, StorageSnapshot, StorageEngine, MvccStore]

**5.** RecoveryManager percorre FileWriteAheadLog; WalRecordCodec descarta cauda incompleta e decodifica CommitRecords válidos. [RecoveryManager, FileWriteAheadLog, WriteAheadLog, WalRecordCodec, CommitRecord]

**6.** Cada CommitRecord posterior ao snapshot é aplicado em ordem, reconstruindo KeyIndex, VersionChain e TtlIndex. [StorageEngine, KeyIndex, VersionChain, VersionedValue, TtlIndex]

**7.** RecoveryManager restaura VersionGenerator na próxima versão disponível. [RecoveryManager, VersionGenerator]

**8.** TempoKvServer libera os endpoints e ServerHealthService publica READY. [TempoKvServer, RespServer, SqlServer, ServerHealthService]

## Fluxos alternativos e falhas

| **Condição**                       | **Comportamento**                                                                  | **Componentes**                 |
|------------------------------------|------------------------------------------------------------------------------------|---------------------------------|
| Snapshot inválido                  | O snapshot é ignorado e o replay começa do WAL completo, quando possível.          | SnapshotStore, RecoveryManager  |
| Registro corrompido no meio do WAL | A recuperação falha de forma segura; somente uma cauda truncada pode ser ignorada. | WalRecordCodec, RecoveryManager |
| Diretório vazio                    | O storage inicia na versão zero.                                                   | RecoveryManager, StorageEngine  |

## Pós-condições

- A versão global é monotônica.

- O estado atual e o histórico retido são idênticos ao estado durável.

- Nenhum cliente observou recuperação parcial.

## Componentes participantes

TempoKvServer, RecoveryManager, SnapshotStore, FileSystemAdapter, StorageSnapshot, StorageEngine, MvccStore, FileWriteAheadLog, WriteAheadLog, WalRecordCodec, CommitRecord, KeyIndex, VersionChain, VersionedValue, TtlIndex, VersionGenerator, RespServer, SqlServer, ServerHealthService, DatabaseLock

# UC-02 — Conectar por RESP e executar PING

| **Objetivo**       | Validar conectividade, parsing RESP, pipeline comum e resposta compatível. |
|--------------------|----------------------------------------------------------------------------|
| **Atores**         | Cliente Redis                                                              |
| **Gatilho**        | O cliente abre uma conexão e envia PING.                                   |
| **Etapa primária** | E2                                                                         |
| **Validação**      | E2.                                                                        |

## Pré-condições

- RespServer em READY.

## Fluxo principal

**1.** RespServer registra o socket no NioEventLoop. [RespServer, NioEventLoop]

**2.** NioEventLoop cria ClientConnection e Session. [NioEventLoop, ClientConnection, Session]

**3.** RespConnectionHandler entrega os bytes ao RespDecoder. [RespConnectionHandler, RespDecoder]

**4.** RespDecoder produz RespFrame; RespCommandMapper cria AdminCommand. [RespDecoder, RespFrame, RespCommandMapper, AdminCommand, Command]

**5.** Authenticator associa a identidade padrão e AccessController autoriza PING. [Authenticator, AccessController, Session]

**6.** CommandValidator valida o comando; CommandDispatcher seleciona AdminCommandHandler. [CommandValidator, CommandDispatcher, CommandHandler, AdminCommandHandler]

**7.** AdminCommandHandler produz CommandResult PONG e registra a operação em MetricsRegistry. [AdminCommandHandler, CommandResult, MetricsRegistry]

**8.** RespEncoder serializa a resposta e NioEventLoop a envia sem bloquear outras conexões. [RespEncoder, NioEventLoop]

## Fluxos alternativos e falhas

| **Condição**     | **Comportamento**                                                                | **Componentes**                                 |
|------------------|----------------------------------------------------------------------------------|-------------------------------------------------|
| Frame incompleto | A conexão preserva os bytes e aguarda a continuação.                             | RespDecoder, ClientConnection                   |
| RESP inválido    | RespEncoder devolve erro de protocolo e a conexão é encerrada quando necessário. | RespDecoder, RespEncoder, RespConnectionHandler |

## Pós-condições

- A conexão pode continuar processando novos comandos.

- A resposta é compreendida pelo redis-cli.

## Componentes participantes

RespServer, NioEventLoop, ClientConnection, Session, RespConnectionHandler, RespDecoder, RespFrame, RespCommandMapper, Command, AdminCommand, Authenticator, AccessController, CommandValidator, CommandDispatcher, CommandHandler, AdminCommandHandler, CommandResult, MetricsRegistry, RespEncoder

# UC-03 — Gravar, consultar e excluir o valor atual com TTL

| **Objetivo**       | Executar o ciclo key-value atual preservando versionamento, autorização, durabilidade configurada e expiração. |
|--------------------|----------------------------------------------------------------------------------------------------------------|
| **Atores**         | Aplicação cliente Redis                                                                                        |
| **Gatilho**        | O cliente envia SET, GET, TTL, EXPIRE ou DEL.                                                                  |
| **Etapa primária** | E3                                                                                                             |
| **Validação**      | E3 valida modo em memória; E5 ativa WAL e E7 adiciona tracing/ACL completo.                                    |

## Pré-condições

- Sessão RESP ativa.

## Fluxo principal

**1.** RespConnectionHandler decodifica e RespCommandMapper cria KeyValueCommand. [RespConnectionHandler, RespDecoder, RespCommandMapper, KeyValueCommand, Command]

**2.** Authenticator e AccessController validam identidade e escopo de chave. [Authenticator, AccessController, Session]

**3.** CommandValidator verifica argumentos, tamanhos, opções e estado da sessão. [CommandValidator]

**4.** CommandDispatcher encaminha para KeyValueCommandHandler. [CommandDispatcher, CommandHandler, KeyValueCommandHandler]

**5.** Para GET/TTL, o handler consulta StorageEngine, que resolve a versão atual em MvccStore/VersionChain e verifica TtlIndex. [KeyValueCommandHandler, StorageEngine, MvccStore, KeyIndex, VersionChain, VersionedValue, TtlIndex]

**6.** Para SET/EXPIRE/DEL, o handler cria Mutation e solicita commit ao CommitCoordinator. [KeyValueCommandHandler, Mutation, CommitCoordinator]

**7.** CommitCoordinator obtém uma versão de VersionGenerator, cria CommitRecord e o anexa ao WriteAheadLog quando a persistência está habilitada. [CommitCoordinator, VersionGenerator, CommitRecord, WriteAheadLog]

**8.** Após a política FsyncPolicy, CommitCoordinator aplica o commit no StorageEngine e atualiza TtlIndex. [FsyncPolicy, CommitCoordinator, StorageEngine, TtlIndex]

**9.** CommandResult retorna valor, OK, inteiro ou nulo; RespEncoder envia a resposta. [CommandResult, RespEncoder, MetricsRegistry, CommandTracer]

## Fluxos alternativos e falhas

| **Condição**                | **Comportamento**                                                                     | **Componentes**                       |
|-----------------------------|---------------------------------------------------------------------------------------|---------------------------------------|
| Condição NX/XX não atendida | Nenhuma Mutation é criada e a resposta indica que não houve alteração.                | KeyValueCommandHandler, CommandResult |
| Chave expirada em leitura   | A leitura considera a chave ausente e o vencimento permanece para limpeza assíncrona. | StorageEngine, TtlIndex               |
| Falha no WAL                | O commit não é publicado no storage e o cliente recebe erro.                          | WriteAheadLog, CommitCoordinator      |

## Pós-condições

- Escritas confirmadas recebem uma nova versão.

- DEL cria tombstone; não destrói o histórico.

- GET nunca observa commit parcialmente aplicado.

## Componentes participantes

RespConnectionHandler, RespDecoder, RespCommandMapper, KeyValueCommand, Command, Authenticator, AccessController, Session, CommandValidator, CommandDispatcher, CommandHandler, KeyValueCommandHandler, StorageEngine, MvccStore, KeyIndex, VersionChain, VersionedValue, TtlIndex, Mutation, CommitCoordinator, VersionGenerator, CommitRecord, WriteAheadLog, FsyncPolicy, CommandResult, RespEncoder, MetricsRegistry, CommandTracer

# UC-04 — Executar consulta ou mutação pela interface SQL

| **Objetivo**       | Oferecer SQL temporal limitado sem duplicar regras de execução ou armazenamento. |
|--------------------|----------------------------------------------------------------------------------|
| **Atores**         | Operador, desenvolvedor ou aplicação administrativa                              |
| **Gatilho**        | O cliente envia SELECT, UPSERT, DELETE ou uma instrução transacional.            |
| **Etapa primária** | E6                                                                               |
| **Validação**      | E6; autorização e tracing completos em E7.                                       |

## Pré-condições

- SqlServer em READY.

- Instrução pertencente ao subconjunto SQL suportado.

## Fluxo principal

**1.** SqlServer aceita a conexão no NioEventLoop e cria ClientConnection/Session. [SqlServer, NioEventLoop, ClientConnection, Session]

**2.** SqlConnectionHandler envia o texto ao TempoLexer. [SqlConnectionHandler, TempoLexer]

**3.** TempoLexer produz tokens e TempoParser constrói Statement/Expression. [TempoLexer, TempoParser, Statement, Expression]

**4.** SqlSemanticAnalyzer valida tipos, nomes, cláusulas temporais e autorização. [SqlSemanticAnalyzer, AccessController, Authenticator]

**5.** SqlPlanner converte a AST em ExecutionPlan. [SqlPlanner, ExecutionPlan]

**6.** PlanExecutor converte operações terminais em Command e usa CommandDispatcher; operadores de projeção e filtro processam CommandResult. [PlanExecutor, Command, CommandDispatcher, CommandResult]

**7.** O handler especializado acessa StorageEngine ou CommitCoordinator conforme leitura ou mutação. [KeyValueCommandHandler, TemporalCommandHandler, TransactionCommandHandler, StorageEngine, CommitCoordinator]

**8.** SqlResultEncoder serializa colunas, linhas, contagens ou erros. [SqlResultEncoder, SqlConnectionHandler, MetricsRegistry, CommandTracer]

## Fluxos alternativos e falhas

| **Condição**             | **Comportamento**                                                              | **Componentes**                           |
|--------------------------|--------------------------------------------------------------------------------|-------------------------------------------|
| Erro léxico ou sintático | A resposta informa linha, coluna e token inesperado; nenhum plano é executado. | TempoLexer, TempoParser, SqlResultEncoder |
| Erro semântico           | A resposta informa coluna, tipo ou cláusula incompatível.                      | SqlSemanticAnalyzer, SqlResultEncoder     |
| Plano não suportado      | SqlPlanner rejeita operações fora do escopo, como JOIN.                        | SqlPlanner, SqlResultEncoder              |

## Pós-condições

- SQL e RESP produzem a mesma semântica para operações equivalentes.

- O storage não conhece SQL.

## Componentes participantes

SqlServer, NioEventLoop, ClientConnection, Session, SqlConnectionHandler, TempoLexer, TempoParser, Statement, Expression, SqlSemanticAnalyzer, SqlPlanner, ExecutionPlan, PlanExecutor, Command, CommandDispatcher, CommandResult, Authenticator, AccessController, KeyValueCommandHandler, TemporalCommandHandler, TransactionCommandHandler, StorageEngine, CommitCoordinator, SqlResultEncoder, MetricsRegistry, CommandTracer

# UC-05 — Consultar valor em uma versão ou instante histórico

| **Objetivo**       | Ler o valor visível de uma chave em um ponto anterior sem alterar o estado atual. |
|--------------------|-----------------------------------------------------------------------------------|
| **Atores**         | Cliente Redis ou SQL                                                              |
| **Gatilho**        | O cliente envia GETAT ou SELECT ... AS OF.                                        |
| **Etapa primária** | E4                                                                                |
| **Validação**      | E4; caminho SQL conectado em E6.                                                  |

## Pré-condições

- A chave possui ou pode ter versões retidas.

## Fluxo principal

**1.** O front-end RESP ou SQL converte a solicitação em TemporalCommand ou ExecutionPlan equivalente. [RespCommandMapper, SqlPlanner, TemporalCommand, ExecutionPlan]

**2.** CommandDispatcher encaminha a operação para TemporalCommandHandler. [CommandDispatcher, TemporalCommandHandler]

**3.** CommandValidator e AccessController validam seletor, retenção e permissão histórica. [CommandValidator, AccessController]

**4.** TemporalCommandHandler consulta StorageEngine com versão ou timestamp. [TemporalCommandHandler, StorageEngine]

**5.** MvccStore localiza a KeyIndex e VersionChain da chave. [MvccStore, KeyIndex, VersionChain]

**6.** VersionChain seleciona a VersionedValue mais recente visível no ponto solicitado. [VersionChain, VersionedValue]

**7.** CommandResult é adaptado por RespEncoder ou SqlResultEncoder. [CommandResult, RespEncoder, SqlResultEncoder, MetricsRegistry, CommandTracer]

## Fluxos alternativos e falhas

| **Condição**               | **Comportamento**                                                              | **Componentes**                         |
|----------------------------|--------------------------------------------------------------------------------|-----------------------------------------|
| Versão anterior à retenção | A resposta indica histórico indisponível, sem confundir com chave inexistente. | RetentionPolicy, TemporalCommandHandler |
| Tombstone visível          | O resultado informa que a chave estava excluída naquele ponto.                 | VersionedValue, CommandResult           |

## Pós-condições

- O estado atual não é modificado.

- A seleção respeita retenção e timestamp de commit.

## Componentes participantes

RespCommandMapper, SqlPlanner, TemporalCommand, ExecutionPlan, CommandDispatcher, TemporalCommandHandler, CommandValidator, AccessController, StorageEngine, MvccStore, KeyIndex, VersionChain, VersionedValue, RetentionPolicy, CommandResult, RespEncoder, SqlResultEncoder, MetricsRegistry, CommandTracer

# UC-06 — Inspecionar histórico e comparar versões

| **Objetivo**       | Listar versões retidas e calcular diferenças entre dois estados da mesma chave. |
|--------------------|---------------------------------------------------------------------------------|
| **Atores**         | Auditor ou operador                                                             |
| **Gatilho**        | O cliente envia HISTORY/DIFF ou consulta SQL equivalente.                       |
| **Etapa primária** | E4                                                                              |
| **Validação**      | E4; SQL em E6; GC é validado novamente em E5/E7.                                |

## Pré-condições

- Permissão de leitura histórica.

## Fluxo principal

**1.** O front-end cria TemporalCommand ou ExecutionPlan de historical scan. [RespCommandMapper, SqlPlanner, TemporalCommand, ExecutionPlan]

**2.** TemporalCommandHandler solicita a cadeia ao StorageEngine. [TemporalCommandHandler, StorageEngine]

**3.** MvccStore percorre VersionChain respeitando HistoryOptions lógicas e RetentionPolicy. [MvccStore, VersionChain, VersionedValue, RetentionPolicy]

**4.** Para DIFF, o handler seleciona duas VersionedValue e calcula diferença de bytes ou representação textual. [TemporalCommandHandler, VersionedValue]

**5.** PlanExecutor aplica filtro, projeção, ordenação e limite quando a origem é SQL. [PlanExecutor, ExecutionPlan]

**6.** CommandResult é registrado em MetricsRegistry e serializado pelo protocolo. [CommandResult, MetricsRegistry, CommandTracer, RespEncoder, SqlResultEncoder]

## Fluxos alternativos e falhas

| **Condição**                            | **Comportamento**                                                              | **Componentes**                         |
|-----------------------------------------|--------------------------------------------------------------------------------|-----------------------------------------|
| Histórico vazio                         | A resposta distingue chave nunca existente de histórico removido por retenção. | RetentionPolicy, TemporalCommandHandler |
| Versões incompatíveis para diff textual | O resultado oferece comparação binária ou metadados, sem corromper os valores. | TemporalCommandHandler, CommandResult   |

## Pós-condições

- Nenhuma versão é modificada.

- A paginação/limite impede respostas ilimitadas.

## Componentes participantes

RespCommandMapper, SqlPlanner, TemporalCommand, ExecutionPlan, TemporalCommandHandler, StorageEngine, MvccStore, VersionChain, VersionedValue, RetentionPolicy, PlanExecutor, CommandResult, MetricsRegistry, CommandTracer, RespEncoder, SqlResultEncoder, HistoryGarbageCollector

# UC-07 — Restaurar uma versão histórica

| **Objetivo**       | Tornar um valor antigo novamente atual sem apagar versões posteriores. |
|--------------------|------------------------------------------------------------------------|
| **Atores**         | Operador autorizado                                                    |
| **Gatilho**        | O cliente envia RESTOREAT ou SQL equivalente.                          |
| **Etapa primária** | E4                                                                     |
| **Validação**      | E4 valida lógica em memória; E5 adiciona durabilidade; E6 conecta SQL. |

## Pré-condições

- A versão solicitada está retida.

- A identidade pode escrever a chave.

## Fluxo principal

**1.** O front-end gera TemporalCommand e o dispatcher seleciona TemporalCommandHandler. [RespCommandMapper, SqlPlanner, TemporalCommand, CommandDispatcher, TemporalCommandHandler]

**2.** AccessController autoriza leitura histórica e escrita na chave. [AccessController, Session]

**3.** TemporalCommandHandler lê a VersionedValue histórica via StorageEngine. [TemporalCommandHandler, StorageEngine, VersionChain, VersionedValue]

**4.** O handler cria uma nova Mutation contendo o valor histórico e metadados de restauração. [TemporalCommandHandler, Mutation]

**5.** CommitCoordinator gera nova versão, cria CommitRecord e registra no WriteAheadLog. [CommitCoordinator, VersionGenerator, CommitRecord, WriteAheadLog]

**6.** Após FsyncPolicy, o commit é aplicado ao MvccStore como nova cabeça da VersionChain. [FsyncPolicy, StorageEngine, MvccStore, VersionChain]

**7.** O cliente recebe a nova versão; as versões intermediárias permanecem consultáveis. [CommandResult, RespEncoder, SqlResultEncoder, MetricsRegistry, CommandTracer]

## Fluxos alternativos e falhas

| **Condição**          | **Comportamento**                              | **Componentes**                         |
|-----------------------|------------------------------------------------|-----------------------------------------|
| Versão não retida     | Nenhum commit é criado.                        | RetentionPolicy, TemporalCommandHandler |
| Conflito em transação | A restauração segue UC-09 e pode ser abortada. | ConflictDetector, TransactionManager    |

## Pós-condições

- A versão restaurada é uma nova versão.

- O histórico permanece append-only.

## Componentes participantes

RespCommandMapper, SqlPlanner, TemporalCommand, CommandDispatcher, TemporalCommandHandler, AccessController, Session, StorageEngine, VersionChain, VersionedValue, Mutation, CommitCoordinator, VersionGenerator, CommitRecord, WriteAheadLog, FsyncPolicy, MvccStore, CommandResult, RespEncoder, SqlResultEncoder, MetricsRegistry, CommandTracer, RetentionPolicy, ConflictDetector, TransactionManager

# UC-08 — Executar transação com snapshot consistente

| **Objetivo**       | Executar múltiplas leituras e escritas com uma visão estável e commit atômico. |
|--------------------|--------------------------------------------------------------------------------|
| **Atores**         | Aplicação cliente                                                              |
| **Gatilho**        | O cliente envia BEGIN, comandos e COMMIT ou ROLLBACK.                          |
| **Etapa primária** | E7                                                                             |
| **Validação**      | E7.                                                                            |

## Pré-condições

- Sessão autenticada sem transação ativa.

## Fluxo principal

**1.** TransactionCommand é encaminhado ao TransactionCommandHandler. [TransactionCommand, CommandDispatcher, TransactionCommandHandler]

**2.** TransactionManager solicita uma versão de snapshot ao SnapshotManager e cria TransactionContext na Session. [TransactionManager, SnapshotManager, TransactionContext, Session]

**3.** Leituras dentro da transação usam StorageEngine limitado à versão do snapshot. [KeyValueCommandHandler, TemporalCommandHandler, StorageEngine, VersionChain, TransactionContext]

**4.** Escritas são representadas por Mutation e acumuladas no write set, sem publicação imediata. [Mutation, TransactionContext, KeyValueCommandHandler]

**5.** No COMMIT, TransactionManager solicita validação ao ConflictDetector. [TransactionManager, ConflictDetector]

**6.** Sem conflitos, CommitCoordinator cria um único CommitRecord com todas as mutações. [CommitCoordinator, CommitRecord, VersionGenerator]

**7.** WAL e StorageEngine recebem o commit atômico; SnapshotManager libera o snapshot. [WriteAheadLog, StorageEngine, SnapshotManager]

**8.** No ROLLBACK, o write set é descartado e nenhuma versão é criada. [TransactionManager, TransactionContext, CommandResult, MetricsRegistry, CommandTracer]

## Fluxos alternativos e falhas

| **Condição**            | **Comportamento**                                           | **Componentes**                             |
|-------------------------|-------------------------------------------------------------|---------------------------------------------|
| COMMIT sem transação    | CommandValidator devolve erro de estado.                    | CommandValidator, TransactionCommandHandler |
| Falha durável no commit | Nenhuma mutação é publicada e a transação termina com erro. | CommitCoordinator, WriteAheadLog            |
| ROLLBACK                | O contexto é encerrado sem tocar no storage.                | TransactionManager, TransactionContext      |

## Pós-condições

- Todas as mutações do commit compartilham a mesma versão.

- As leituras foram consistentes com o snapshot.

## Componentes participantes

TransactionCommand, CommandDispatcher, TransactionCommandHandler, TransactionManager, SnapshotManager, TransactionContext, Session, KeyValueCommandHandler, TemporalCommandHandler, StorageEngine, VersionChain, Mutation, ConflictDetector, CommitCoordinator, CommitRecord, VersionGenerator, WriteAheadLog, CommandValidator, CommandResult, MetricsRegistry, CommandTracer

# UC-09 — Detectar e abortar conflito concorrente

| **Objetivo**       | Impedir perda silenciosa de atualização quando transações concorrentes escrevem a mesma chave. |
|--------------------|------------------------------------------------------------------------------------------------|
| **Atores**         | Duas ou mais aplicações clientes                                                               |
| **Gatilho**        | A segunda transação tenta confirmar uma chave modificada após seu snapshot.                    |
| **Etapa primária** | E7                                                                                             |
| **Validação**      | E7.                                                                                            |

## Pré-condições

- Duas sessões possuem snapshots ativos.

## Fluxo principal

**1.** Cada Session mantém seu TransactionContext e snapshotVersion. [Session, TransactionContext, SnapshotManager]

**2.** A primeira transação é confirmada pelo CommitCoordinator e cria uma nova versão. [TransactionManager, CommitCoordinator, VersionGenerator, CommitRecord, StorageEngine]

**3.** A segunda transação solicita COMMIT ao TransactionCommandHandler. [TransactionCommand, TransactionCommandHandler]

**4.** ConflictDetector compara as chaves do write set com as versões atuais no StorageEngine. [ConflictDetector, TransactionContext, StorageEngine, VersionChain]

**5.** Ao encontrar versão superior ao snapshot, ConflictDetector rejeita o commit antes do WAL. [ConflictDetector, WriteAheadLog]

**6.** TransactionManager marca o contexto como abortado e SnapshotManager libera o snapshot. [TransactionManager, TransactionContext, SnapshotManager]

**7.** CommandResult identifica as chaves conflitantes sem expor valores. [CommandResult, RespEncoder, SqlResultEncoder, MetricsRegistry, CommandTracer]

## Fluxos alternativos e falhas

| **Condição**            | **Comportamento**                                           | **Componentes**                     |
|-------------------------|-------------------------------------------------------------|-------------------------------------|
| Chaves diferentes       | As duas transações podem confirmar.                         | ConflictDetector, CommitCoordinator |
| Reexecução pelo cliente | Uma nova transação recebe snapshot atual e repete a lógica. | TransactionManager, SnapshotManager |

## Pós-condições

- Nenhum CommitRecord é criado para a transação abortada.

- O estado confirmado pela primeira transação permanece íntegro.

## Componentes participantes

Session, TransactionContext, SnapshotManager, TransactionManager, CommitCoordinator, VersionGenerator, CommitRecord, StorageEngine, TransactionCommand, TransactionCommandHandler, ConflictDetector, VersionChain, WriteAheadLog, CommandResult, RespEncoder, SqlResultEncoder, MetricsRegistry, CommandTracer

# UC-10 — Expirar chave automaticamente preservando o evento histórico

| **Objetivo**       | Tornar uma chave invisível após o TTL e registrar a expiração como nova versão. |
|--------------------|---------------------------------------------------------------------------------|
| **Atores**         | TempoKvServer / aplicação cliente                                               |
| **Gatilho**        | O relógio alcança o próximo vencimento do TtlIndex.                             |
| **Etapa primária** | E5                                                                              |
| **Validação**      | E5.                                                                             |

## Pré-condições

- Existe uma versão atual com prazo de expiração.

## Fluxo principal

**1.** ExpirationWorker consulta o TtlIndex nos intervalos configurados. [ExpirationWorker, TtlIndex]

**2.** O worker confirma no StorageEngine que a entrada ainda corresponde à versão atual. [ExpirationWorker, StorageEngine, VersionChain]

**3.** ExpirationWorker cria Mutation tombstone com motivo EXPIRED. [ExpirationWorker, Mutation]

**4.** CommitCoordinator gera versão e CommitRecord. [CommitCoordinator, VersionGenerator, CommitRecord]

**5.** O commit é anexado ao WriteAheadLog e sincronizado por FsyncPolicy. [WriteAheadLog, FsyncPolicy]

**6.** StorageEngine publica o tombstone e remove a entrada vencida do TtlIndex. [StorageEngine, VersionedValue, TtlIndex]

**7.** Leituras atuais retornam ausente; HISTORY continua mostrando valor e expiração. [KeyValueCommandHandler, TemporalCommandHandler, MetricsRegistry, CommandTracer]

## Fluxos alternativos e falhas

| **Condição**                  | **Comportamento**                                                               | **Componentes**                             |
|-------------------------------|---------------------------------------------------------------------------------|---------------------------------------------|
| Entrada obsoleta no TtlIndex  | O worker a descarta porque outra versão alterou ou removeu a chave.             | ExpirationWorker, TtlIndex, VersionChain    |
| Servidor parado no vencimento | UC-01 reconstrói o TTL e a primeira leitura/worker aplica a expiração pendente. | RecoveryManager, TtlIndex, ExpirationWorker |

## Pós-condições

- A chave não é visível após o prazo.

- O evento de expiração é auditável.

## Componentes participantes

ExpirationWorker, TtlIndex, StorageEngine, VersionChain, Mutation, CommitCoordinator, VersionGenerator, CommitRecord, WriteAheadLog, FsyncPolicy, VersionedValue, KeyValueCommandHandler, TemporalCommandHandler, MetricsRegistry, CommandTracer, RecoveryManager

# UC-11 — Criar snapshot e compactar o WAL

| **Objetivo**       | Reduzir o custo de recuperação e o espaço do WAL sem perder versões retidas ou dados de réplica. |
|--------------------|--------------------------------------------------------------------------------------------------|
| **Atores**         | TempoKvServer / operador                                                                         |
| **Gatilho**        | Limite de WAL, agenda ou comando administrativo solicita snapshot.                               |
| **Etapa primária** | E5                                                                                               |
| **Validação**      | E5 no modo single-node; regras de ACK são completadas em E8.                                     |

## Pré-condições

- StorageEngine em funcionamento.

- Não existe publicação de snapshot concorrente.

## Fluxo principal

**1.** SnapshotWriter solicita ao SnapshotManager ou StorageEngine uma versão de corte consistente. [SnapshotWriter, SnapshotManager, StorageEngine]

**2.** MvccStore materializa StorageSnapshot contendo KeyIndex, VersionChain retida e TtlIndex. [MvccStore, StorageSnapshot, KeyIndex, VersionChain, VersionedValue, TtlIndex]

**3.** SnapshotStore grava arquivo temporário por FileSystemAdapter e o publica por rename atômico. [SnapshotStore, FileSystemAdapter]

**4.** SnapshotWriter valida a publicação e registra métricas. [SnapshotWriter, MetricsRegistry, CommandTracer]

**5.** HistoryGarbageCollector aplica RetentionPolicy somente em versões não necessárias por snapshots ativos. [HistoryGarbageCollector, RetentionPolicy, SnapshotManager]

**6.** WalCompactor calcula o menor ponto necessário por snapshot e AckTracker. [WalCompactor, AckTracker]

**7.** WalCompactor remove segmentos cobertos do FileWriteAheadLog. [WalCompactor, FileWriteAheadLog, WriteAheadLog]

**8.** ServerHealthService sinaliza DEGRADED se a compactação falhar, sem invalidar o snapshot anterior. [ServerHealthService, SnapshotStore]

## Fluxos alternativos e falhas

| **Condição**          | **Comportamento**                                                                       | **Componentes**                           |
|-----------------------|-----------------------------------------------------------------------------------------|-------------------------------------------|
| Falha antes do rename | O arquivo temporário é ignorado e o snapshot anterior permanece válido.                 | SnapshotStore, FileSystemAdapter          |
| Réplica atrasada      | WalCompactor preserva segmentos ainda necessários ou força nova sincronização completa. | AckTracker, WalCompactor, SyncCoordinator |
| Snapshot ativo antigo | HistoryGarbageCollector adia remoção das versões visíveis.                              | HistoryGarbageCollector, SnapshotManager  |

## Pós-condições

- Existe ao menos um snapshot válido.

- O WAL restante é suficiente para recuperação e replicação.

## Componentes participantes

SnapshotWriter, SnapshotManager, StorageEngine, MvccStore, StorageSnapshot, KeyIndex, VersionChain, VersionedValue, TtlIndex, SnapshotStore, FileSystemAdapter, MetricsRegistry, CommandTracer, HistoryGarbageCollector, RetentionPolicy, WalCompactor, AckTracker, FileWriteAheadLog, WriteAheadLog, ServerHealthService, SyncCoordinator

# UC-12 — Consultar saúde, métricas e informações administrativas

| **Objetivo**       | Expor estado operacional suficiente para depuração, demonstração e integração com monitoramento. |
|--------------------|--------------------------------------------------------------------------------------------------|
| **Atores**         | Operador, monitor ou cliente autorizado                                                          |
| **Gatilho**        | O cliente envia PING, HEALTH ou INFO.                                                            |
| **Etapa primária** | E7                                                                                               |
| **Validação**      | E7.                                                                                              |

## Pré-condições

- Endpoint ativo.

## Fluxo principal

**1.** O front-end cria AdminCommand. [RespCommandMapper, SqlPlanner, AdminCommand]

**2.** Authenticator e AccessController verificam a permissão administrativa. [Authenticator, AccessController, Session]

**3.** CommandDispatcher seleciona AdminCommandHandler. [CommandDispatcher, AdminCommandHandler]

**4.** AdminCommandHandler consulta ServerHealthService e MetricsRegistry. [AdminCommandHandler, ServerHealthService, MetricsRegistry]

**5.** CommandTracer fornece agregados de latência e erro, sem expor conteúdo de chaves. [CommandTracer]

**6.** CommandResult é serializado por RespEncoder ou SqlResultEncoder. [CommandResult, RespEncoder, SqlResultEncoder]

## Fluxos alternativos e falhas

| **Condição**          | **Comportamento**                                                             | **Componentes**                          |
|-----------------------|-------------------------------------------------------------------------------|------------------------------------------|
| Usuário sem permissão | AccessController retorna erro de autorização.                                 | AccessController, CommandResult          |
| Subsistema degradado  | HEALTH informa DEGRADED com códigos estáveis, mantendo o endpoint responsivo. | ServerHealthService, AdminCommandHandler |

## Pós-condições

- O operador conhece papel, versão, clientes, WAL, snapshots, réplica e latências.

- Nenhum valor de usuário é retornado por INFO.

## Componentes participantes

RespCommandMapper, SqlPlanner, AdminCommand, Authenticator, AccessController, Session, CommandDispatcher, AdminCommandHandler, ServerHealthService, MetricsRegistry, CommandTracer, CommandResult, RespEncoder, SqlResultEncoder

# UC-13 — Replicar commits para uma réplica e servir leitura read-only

| **Objetivo**       | Manter uma cópia ordenada do estado e permitir leitura em réplica sem geração independente de versões. |
|--------------------|--------------------------------------------------------------------------------------------------------|
| **Atores**         | Nó primário, nó réplica e aplicação leitora                                                            |
| **Gatilho**        | ReplicaClient conecta ao PrimaryReplicationEndpoint.                                                   |
| **Etapa primária** | E8                                                                                                     |
| **Validação**      | E8.                                                                                                    |

## Pré-condições

- Primário e réplica possuem configuração compatível.

- A réplica não aceita mutações de clientes.

## Fluxo principal

**1.** ReplicationManager inicializa ReplicaState conforme o papel configurado. [ReplicationManager, ReplicaState, ServerConfiguration]

**2.** ReplicaClient estabelece handshake com PrimaryReplicationEndpoint e informa a última versão aplicada. [ReplicaClient, PrimaryReplicationEndpoint, ReplicaState]

**3.** SyncCoordinator decide entre snapshot completo pelo SnapshotStore ou commits incrementais pelo WriteAheadLog. [SyncCoordinator, SnapshotStore, WriteAheadLog, WalRecordCodec]

**4.** Na sincronização completa, ReplicaApplier instala StorageSnapshot antes de liberar leituras. [ReplicaApplier, StorageSnapshot, StorageEngine, ServerHealthService]

**5.** Na sincronização incremental, o primário transmite CommitRecords em ordem. [PrimaryReplicationEndpoint, CommitRecord, ReplicationManager]

**6.** ReplicaClient entrega cada registro ao ReplicaApplier, que valida e aplica no StorageEngine sem chamar VersionGenerator. [ReplicaClient, ReplicaApplier, StorageEngine, VersionGenerator]

**7.** ReplicaState atualiza a versão aplicada e AckTracker registra a confirmação no primário. [ReplicaState, AckTracker, ReplicationManager]

**8.** RespServer e SqlServer da réplica permitem leituras; AccessController rejeita mutações. [RespServer, SqlServer, AccessController, ServerHealthService]

**9.** Novos commits do CommitCoordinator são publicados pelo ReplicationManager após confirmação local. [CommitCoordinator, ReplicationManager, MetricsRegistry, CommandTracer]

## Fluxos alternativos e falhas

| **Condição**                      | **Comportamento**                                             | **Componentes**                 |
|-----------------------------------|---------------------------------------------------------------|---------------------------------|
| WAL não cobre a versão da réplica | SyncCoordinator exige snapshot completo.                      | SyncCoordinator, SnapshotStore  |
| Conexão interrompida              | ReplicaClient reconecta a partir da última versão confirmada. | ReplicaClient, ReplicaState     |
| Commit fora de ordem              | ReplicaApplier rejeita o registro e reinicia sincronização.   | ReplicaApplier, SyncCoordinator |
| Escrita enviada à réplica         | AccessController retorna READONLY.                            | AccessController, CommandResult |

## Pós-condições

- A réplica aplica exatamente a ordem do primário.

- A réplica nunca gera versões próprias.

- A defasagem é observável.

## Componentes participantes

ReplicationManager, ReplicaState, ServerConfiguration, ReplicaClient, PrimaryReplicationEndpoint, SyncCoordinator, SnapshotStore, WriteAheadLog, WalRecordCodec, ReplicaApplier, StorageSnapshot, StorageEngine, ServerHealthService, CommitRecord, VersionGenerator, AckTracker, RespServer, SqlServer, AccessController, CommitCoordinator, MetricsRegistry, CommandTracer, CommandResult

# Matriz de rastreabilidade

| **UC** | **Fluxo**                                                    | **Etapas**     | **Classes** | **Componentes principais**                                                                                                                                                                         |
|--------|--------------------------------------------------------------|----------------|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| UC-00  | Iniciar uma instância local ou por Docker                    | E1, E8         | 13          | TempoKvApplication, ServerConfiguration, TempoKvServer, FileSystemAdapter, DatabaseLock, RecoveryManager, RespServer, SqlServer, ExpirationWorker, HistoryGarbageCollector…                        |
| UC-01  | Recuperar o estado após reinício ou falha                    | E5, E8         | 20          | TempoKvServer, RecoveryManager, SnapshotStore, FileSystemAdapter, StorageSnapshot, StorageEngine, MvccStore, FileWriteAheadLog, WriteAheadLog, WalRecordCodec…                                     |
| UC-02  | Conectar por RESP e executar PING                            | E2             | 19          | RespServer, NioEventLoop, ClientConnection, Session, RespConnectionHandler, RespDecoder, RespFrame, RespCommandMapper, Command, AdminCommand…                                                      |
| UC-03  | Gravar, consultar e excluir o valor atual com TTL            | E3, E5, E7     | 28          | RespConnectionHandler, RespDecoder, RespCommandMapper, KeyValueCommand, Command, Authenticator, AccessController, Session, CommandValidator, CommandDispatcher…                                    |
| UC-04  | Executar consulta ou mutação pela interface SQL              | E6, E7         | 26          | SqlServer, NioEventLoop, ClientConnection, Session, SqlConnectionHandler, TempoLexer, TempoParser, Statement, Expression, SqlSemanticAnalyzer…                                                     |
| UC-05  | Consultar valor em uma versão ou instante histórico          | E4, E6, E7     | 19          | RespCommandMapper, SqlPlanner, TemporalCommand, ExecutionPlan, CommandDispatcher, TemporalCommandHandler, CommandValidator, AccessController, StorageEngine, MvccStore…                            |
| UC-06  | Inspecionar histórico e comparar versões                     | E4, E6, E7     | 17          | RespCommandMapper, SqlPlanner, TemporalCommand, ExecutionPlan, TemporalCommandHandler, StorageEngine, MvccStore, VersionChain, VersionedValue, RetentionPolicy…                                    |
| UC-07  | Restaurar uma versão histórica                               | E4, E5, E6, E7 | 25          | RespCommandMapper, SqlPlanner, TemporalCommand, CommandDispatcher, TemporalCommandHandler, AccessController, Session, StorageEngine, VersionChain, VersionedValue…                                 |
| UC-08  | Executar transação com snapshot consistente                  | E5, E7         | 21          | TransactionCommand, CommandDispatcher, TransactionCommandHandler, TransactionManager, SnapshotManager, TransactionContext, Session, KeyValueCommandHandler, TemporalCommandHandler, StorageEngine… |
| UC-09  | Detectar e abortar conflito concorrente                      | E7             | 18          | Session, TransactionContext, SnapshotManager, TransactionManager, CommitCoordinator, VersionGenerator, CommitRecord, StorageEngine, TransactionCommand, TransactionCommandHandler…                 |
| UC-10  | Expirar chave automaticamente preservando o evento histórico | E5             | 16          | ExpirationWorker, TtlIndex, StorageEngine, VersionChain, Mutation, CommitCoordinator, VersionGenerator, CommitRecord, WriteAheadLog, FsyncPolicy…                                                  |
| UC-11  | Criar snapshot e compactar o WAL                             | E5, E8         | 21          | SnapshotWriter, SnapshotManager, StorageEngine, MvccStore, StorageSnapshot, KeyIndex, VersionChain, VersionedValue, TtlIndex, SnapshotStore…                                                       |
| UC-12  | Consultar saúde, métricas e informações administrativas      | E7, E8         | 14          | RespCommandMapper, SqlPlanner, AdminCommand, Authenticator, AccessController, Session, CommandDispatcher, AdminCommandHandler, ServerHealthService, MetricsRegistry…                               |
| UC-13  | Replicar commits para uma réplica e servir leitura read-only | E8             | 23          | ReplicationManager, ReplicaState, ServerConfiguration, ReplicaClient, PrimaryReplicationEndpoint, SyncCoordinator, SnapshotStore, WriteAheadLog, WalRecordCodec, ReplicaApplier…                   |

Regra de consistência: todo caso de uso acima aparece em pelo menos uma etapa do plano; todas as classes citadas existem no diagrama conceitual.
