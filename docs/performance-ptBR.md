[English](performance.md) | **Português (Brasil)**

# Desempenho, Profiling e Benchmarks

## Escopo

O harness de benchmarks do TempoKV cobre armazenamento em processo, operações
temporais, processamento de commits, persistência, protocolos, transações,
replicação e sockets reais em loopback. Os resultados numéricos publicados
abaixo estão limitados às execuções cujos dados brutos permaneceram
identificáveis e cujo caminho medido não foi invalidado por uma alteração
posterior.

Esses resultados caracterizam uma máquina de desenvolvimento. Eles não são um
SLA, promessa de capacidade ou garantia de compatibilidade. Não os extrapole
para outra CPU, heap, filesystem, política de durabilidade, rede, dataset ou
nível de concorrência. Não foram medidos uma implantação com vários hosts, uma
carga de produção sustentada nem um limite máximo de capacidade.

## Ambiente de testes

Os metadados dos resultados preservados e a inspeção da máquina identificam
este ambiente:

| Item | Valor confirmado |
| --- | --- |
| Data da medição | 2026-07-29 |
| CPU | AMD Ryzen 5 7520U with Radeon Graphics |
| Topologia da CPU | 4 núcleos físicos, 8 threads de hardware |
| Memória visível ao sistema operacional | 7.0 GiB |
| Sistema operacional | Manjaro Linux, x86_64, kernel 6.12.77-1-MANJARO |
| Filesystem do projeto | Btrfs com compressão zstd em NVMe |
| Filesystem temporário em memória | tmpfs, usado quando o estado do benchmark solicitava um diretório temporário |
| JDK dos benchmarks | Eclipse Temurin 25.0.3 |
| JMH | 1.37 |
| Heap publicado de histórico | 768 MiB fixos (`-Xms768m -Xmx768m`) |
| Heap publicado do compilador SQL | 512 MiB fixos (`-Xms512m -Xmx512m`) |
| Forks e threads publicados | 1 fork, 1 thread |
| Durabilidade | Não aplicável: os resultados publicados de histórico e compilador SQL são microbenchmarks em memória |

Os detalhes de filesystem e tmpfs são registrados para reprodutibilidade, mas
nenhum deles está no caminho medido dos resultados em memória publicados.

## Metodologia

O JMH faz aquecimento antes da medição e consome os valores retornados.
Benchmarks que emitem vários resultados usam `Blackhole` quando apropriado,
impedindo que o trabalho medido seja eliminado como código morto. As anotações
das classes definem os padrões normais; uma execução pela linha de comando pode
sobrescrevê-los.

A comparação histórica antes/depois usou `Mode.AverageTime`, uma iteração de
aquecimento de 300 ms, três iterações de medição de 500 ms, um fork, uma thread
e `ns/op`. A execução preservada do compilador SQL usou `Mode.Throughput`, três
iterações de aquecimento de 500 ms, cinco iterações de medição de 1 s, um fork,
uma thread e o profiler de GC do JMH.

Resultados em `Mode.SampleTime` incluem uma distribuição de latência e
percentis; `Mode.Throughput` informa operações por unidade de tempo;
`Mode.AverageTime` informa tempo médio por operação. Essas métricas não são
intercambiáveis. Um fork e iterações curtas são evidências úteis de engenharia,
mas não demonstram significância estatística nem um intervalo de confiança
forte.

O harness possui três níveis distintos:

- microbenchmarks em processo isolam componentes Java e excluem o custo de
  sockets e processos;
- `NetworkProtocolBenchmark` usa sockets TCP reais em loopback e inclui
  framing, o endpoint NIO e o escalonamento de cliente/servidor;
- testes de integração exercitam o comportamento completo, mas são testes de
  corretude e não publicam assertions de tempo.

`FsyncPolicy.NEVER` e `FsyncPolicy.ALWAYS` oferecem garantias de durabilidade
diferentes. Seus valores de throughput ou latência não devem ser apresentados
como uma comparação de desempenho equivalente.

Fontes conhecidas de ruído incluem ajuste de frequência da CPU, estado térmico,
processos em segundo plano, garbage collection, estado do cache do filesystem,
tmpfs em comparação com armazenamento persistente, comportamento do filesystem
comprimido e escalonamento do loopback. Nenhuma afirmação de significância
estatística é feita.

## Cobertura dos benchmarks

| Área | Classe de benchmark | Cargas representativas |
| --- | --- | --- |
| Leituras do armazenamento atual | `StorageReadBenchmark` | `currentGetHit`, `currentGetMiss` |
| Leituras temporais e diff | `StorageReadBenchmark` | versão mais nova/antiga, timestamp mais antigo, histórico raso/profundo, `DIFF` próximo/distante |
| Escala histórica focada | `HistoricalLookupBenchmark` | atual, meio, mais antigo, timestamp, `DIFF` próximo/distante, append nas profundidades 1.000–100.000 |
| Escritas e manutenção do armazenamento | `StorageWriteBenchmark` | `SET` novo/existente, tombstone, expiração, commit com várias mutações, append profundo, restauração, GC |
| Protocolos em processo | `ProtocolBenchmark` | pipelines RESP, parse/plan SQL, caminho completo do handler SQL atual |
| Protocolo real em loopback | `NetworkProtocolBenchmark` | RESP `PING`/`GET`/`SET`, pipelines com 1–8 clientes, SQL atual e histórico |
| Escrita de WAL e snapshot | `PersistenceBenchmark` | encode/append do WAL com `NEVER` e `ALWAYS`, escrita de snapshot |
| Recovery e compactação | `PersistenceLifecycleBenchmark` | replay/rotação/compactação do WAL, carga de snapshot, recovery de snapshot mais WAL |
| Transações | `TransactionBenchmark` | commit vazio/uma chave/várias chaves, rollback, conflito determinístico, 1–8 clientes sem conflito |
| Caminho de commit da replicação | `ReplicationBenchmark` | commit primário sem réplica e commit mais ACK da réplica |
| Caminho de dados da replicação | `ReplicationDataBenchmark` | aplicação na réplica, instalação de snapshot completo, plano de catch-up incremental |

`BenchmarkFixtures` é código compartilhado de setup, não uma classe de
benchmark.

## Resultados representativos

### Lookup histórico antes e depois dos checkpoints esparsos

O baseline é o commit `2ea843b`; o resultado posterior é o commit `5f72f9f`.
Os dois conjuntos usaram a mesma fonte do benchmark e as mesmas configurações
JMH pela linha de comando descritas acima. “Redução” é a redução do tempo médio
medido, não uma afirmação de confiança estatística.

| Carga | Profundidade | Antes | Depois | Redução do tempo médio |
| --- | ---: | ---: | ---: | ---: |
| `currentVersion` | 1.000 | 33.5 ns/op | 24.1 ns/op | 28.2% |
| `currentVersion` | 10.000 | 37.1 ns/op | 22.8 ns/op | 38.7% |
| `currentVersion` | 100.000 | 40.1 ns/op | 22.6 ns/op | 43.6% |
| `middleVersion` | 1.000 | 1,237.8 ns/op | 76.6 ns/op | 93.8% |
| `middleVersion` | 10.000 | 9,008.2 ns/op | 471.8 ns/op | 94.8% |
| `middleVersion` | 100.000 | 708,491.8 ns/op | 3,146.0 ns/op | 99.6% |
| `oldestVersion` | 1.000 | 1,816.7 ns/op | 170.1 ns/op | 90.6% |
| `oldestVersion` | 10.000 | 18,861.5 ns/op | 677.7 ns/op | 96.4% |
| `oldestVersion` | 100.000 | 1,288,275.9 ns/op | 8,743.1 ns/op | 99.3% |
| `middleTimestamp` | 1.000 | 2,365.2 ns/op | 181.3 ns/op | 92.3% |
| `middleTimestamp` | 10.000 | 45,386.6 ns/op | 519.2 ns/op | 98.9% |
| `middleTimestamp` | 100.000 | 2,038,080.4 ns/op | 4,205.5 ns/op | 99.8% |
| `oldestTimestamp` | 1.000 | 4,319.0 ns/op | 252.5 ns/op | 94.2% |
| `oldestTimestamp` | 10.000 | 29,744.8 ns/op | 923.2 ns/op | 96.9% |
| `oldestTimestamp` | 100.000 | 3,333,885.6 ns/op | 13,154.6 ns/op | 99.6% |
| `diffNear` | 1.000 | 6,455.3 ns/op | 1,499.7 ns/op | 76.8% |
| `diffNear` | 10.000 | 1,318.1 ns/op | 572.8 ns/op | 56.5% |
| `diffNear` | 100.000 | 2,562.0 ns/op | 1,629.8 ns/op | 36.4% |
| `diffDistant` | 1.000 | 5,530.9 ns/op | 928.0 ns/op | 83.2% |
| `diffDistant` | 10.000 | 28,677.1 ns/op | 1,471.5 ns/op | 94.9% |
| `diffDistant` | 100.000 | 1,796,573.0 ns/op | 11,122.6 ns/op | 99.4% |
| `append` | 1.000 | 3,573.3 ns/op | 655.4 ns/op | 81.7% |
| `append` | 10.000 | 857.1 ns/op | 684.9 ns/op | 20.1% |
| `append` | 100.000 | 3,023.1 ns/op | 805.5 ns/op | 73.4% |

Os valores não monotônicos do baseline para `append` e `diffNear` mostram por
que essas execuções curtas, com um fork, devem ser interpretadas como evidência
de engenharia, e não como razões universais. Os valores posteriores são
informados sem alegar um nível de confiança que não foi medido.

### Microbenchmarks do compilador SQL

Nenhum commit posterior alterou o caminho de lexer, parser, analisador
semântico ou planner medido por esses dois métodos.

| Carga | Dataset/histórico | Concorrência | Durabilidade | Métrica | Resultado | Ressalva |
| --- | --- | ---: | --- | --- | ---: | --- |
| `sqlParsePlanCurrent` | Uma instrução `SELECT` pontual | 1 thread | N/A | Throughput | 92,848.5 ops/s | Um fork; somente compilação em processo |
| `sqlParsePlanCurrent` | Uma instrução `SELECT` pontual | 1 thread | N/A | Alocação | 35,218.7 B/op | Estimativa do profiler de GC do JMH |
| `sqlParsePlanHistorical` | Uma instrução `AS OF VERSION` | 1 thread | N/A | Throughput | 86,572.4 ops/s | Um fork; somente compilação em processo |
| `sqlParsePlanHistorical` | Uma instrução `AS OF VERSION` | 1 thread | N/A | Alocação | 35,684.3 B/op | Estimativa do profiler de GC do JMH |

### Resultados deliberadamente não publicados

| Área | Motivo |
| --- | --- |
| Pipeline RESP e alocação | Os dados brutos preservados são anteriores às mudanças posteriores de autenticação, limites do decoder e handlers |
| TCP real após `TCP_NODELAY` | O artefato numérico final não foi preservado |
| `SET` após o append da cadeia imutável | O baseline preservado é anterior à mudança para cadeia encadeada; não resta JSON final comparável |
| WAL, recovery, snapshot e compactação | Mudanças posteriores no snapshot limitado afetam o caminho, e não resta resultado final comparável |
| Transações | Não resta artefato do resultado final |
| Replicação | Não resta artefato do resultado final após as mudanças de heartbeat e limites de fila |

O JSON bruto da comparação histórica e das execuções diagnósticas antigas
sobreviveu apenas como artefato temporário local. Ele não é versionado nem
linkado por este documento; as tabelas acima preservam os valores revisados.

## Escalabilidade do lookup histórico

`VersionChain` continua sendo uma cadeia encadeada imutável, da versão mais nova
para a mais antiga. Agora ela adiciona um checkpoint esparso a cada 64 versões.
Um lookup percorre os checkpoints em direção à coordenada solicitada e então
percorre os nós restantes. Leituras atuais continuam sendo um acesso O(1) ao
head, e append continua O(1), incluindo publicação atômica do head.

Lookups por versão e timestamp monotônico têm trabalho esperado
O(profundidade / 64 + 64). Isso ainda é O(profundidade) assintoticamente, não
um índice logarítmico, mas os lookups profundos medidos apresentam uma redução
substancial do fator constante. Se timestamps recuperados não forem
monotônicos, o lookup por timestamp volta com segurança para uma varredura
O(profundidade). `DIFF` executa dois lookups pontuais mais uma comparação
proporcional aos bytes dos valores comparados. `HISTORY` continua proporcional
à quantidade de versões materializadas e retornadas.

O formato de memória adicional é O(profundidade / 64): um objeto de checkpoint
com duas referências para cada bloco de 64 versões. Os bytes retidos exatos não
foram medidos, portanto nenhuma estimativa de bytes por versão é alegada.
Checkpoints são reconstruídos a partir da cadeia imutável durante recovery e
não são serializados nos formatos de WAL ou snapshot.

As profundidades medidas foram 1.000, 10.000 e 100.000. Históricos maiores,
distribuições de timestamp não monotônicas e append concorrente com alta
contenção não foram caracterizados para publicação.

## Achados de profiling

| Carga | Evidência | Mudança | Observação posterior | Custo restante |
| --- | --- | --- | --- | --- |
| Validação de nomes de métricas | Correlação de profile e inspeção do código identificaram trabalho de regex com `String.matches` por chamada | O matching por regex foi substituído por varredura direta de caracteres | A preparação do engine de regex não existe no caminho atual; não há número posterior preservado do profiler | Atualizações do mapa de métricas e agregação permanecem |
| Pipelines RESP | Diagnósticos com o profiler de GC do JMH mostraram alocação crescendo com o tamanho do pipeline | Foram adicionados limites defensivos de entrada, escritas pendentes limitadas e limpeza do estado de erro | Os valores numéricos antigos de alocação não são publicados porque mudanças posteriores nos handlers afetam a comparabilidade | `ByteArrayOutputStream.toByteArray`, cópias de arrays de frames, arrays de respostas e buffers de fila permanecem |
| Parsing e planejamento SQL | Caminho atual do compilador mais saída preservada do profiler de GC do JMH | Não foi feita uma reescrita ampla do lexer/parser | Aproximadamente 35 KiB/op permaneceram nos microbenchmarks preservados do compilador | Objetos de lexer, parser CUP, AST, análise semântica e plano são alocados por instrução |
| Lookup histórico pontual | Resultados JMH comparáveis antes/depois em três profundidades | Foram adicionados checkpoints esparsos a cada 64 versões sem mudar a cadeia imutável nem formatos persistidos | As médias profundas de versão, timestamp e `DIFF` distante caíram substancialmente na faixa testada | O lookup continua O(profundidade) assintoticamente; `HISTORY` precisa materializar os registros retornados |
| Append em cadeia profunda | A inspeção do código confirmou cópia integral da lista em cada append imutável | A cópia integral da lista foi substituída por prepend de nó encadeado imutável | A execução posterior focada permaneceu abaixo de 806 ns/op até a profundidade 100.000 | Construção do commit, cópias defensivas dos valores e criação de checkpoints permanecem |
| Snapshot e recovery | A inspeção do código encontrou verificações tardias de tamanho e materialização integral de buffers | Foram adicionados serialização limitada, aborto antecipado, limpeza de arquivo temporário e escrita direta no arquivo temporário | A falha passa a ser limitada mais cedo; não resta artefato final comparável de tempo | Encode/carga do snapshot e replay do WAL ainda materializam arrays de bytes |
| TCP em loopback | O benchmark de socket e a inspeção do código expuseram atraso em escritas pequenas | `TCP_NODELAY` foi habilitado nos sockets aceitos de clientes e replicação | Não resta resultado numérico final; nenhuma alegação de desempenho é feita | Um selector NIO atende cada protocolo público, e o ruído de escalonamento permanece |
| Contenção | Benchmarks de concorrência de transações e rede existem, mas nenhum trace preservado estabelece um hotspot universal | Nenhum lock global de leitura foi adicionado | Testes de corretude e cobertura de benchmark permanecem | Um selector por protocolo e seções sincronizadas de commit/storage podem serializar trabalho |

Não havia gravação preservada de NMT ou async-profiler. Nenhuma gravação `.jfr`
é publicada. A tabela diferencia causas no código, correlação de profiler e
melhoria medida, em vez de tratar uma amostra como prova universal.

## Hardening relacionado a desempenho

A configuração atual valida limites de conexões por protocolo, elementos de
array RESP, tamanhos de comando e credenciais, mutações e bytes do write set de
transação, peers de replicação, commits e bytes pendentes por réplica e bytes de
snapshot. Valores inválidos ou incoerentes falham durante o startup.

Comportamentos adicionais de segurança sob carga incluem:

- filas de escrita pendente limitadas por cliente e backpressure de leitura NIO;
- deduplicação de TTL por chave, para que expirações substituídas não se
  acumulem;
- filas limitadas de réplica com desconexão de réplica lenta;
- aplicação antecipada do limite de snapshot e remoção de snapshots temporários
  com falha;
- timeouts de heartbeat que liberam conexões de replicação mortas ou half-open;
- frames de replicação limitados e timeouts de sincronização.

Esses controles trocam retenção ilimitada de recursos por rejeição ou
desconexão explícita. Eles são comportamento de estabilidade, não evidência de
throughput maior.

## Limitações conhecidas

- RESP ainda executa cópias evitáveis de arrays de bytes e framing.
- SQL aloca objetos de lexer, parser, AST, semântica e planejamento por
  instrução.
- Cada protocolo público usa um selector; sharding de selectors não foi medido.
- A escala publicada termina em um histórico de 100.000 versões e uma thread de
  benchmark.
- Os caminhos de snapshot e full sync ainda materializam snapshots limitados em
  vez de fazer streaming de todo o codec.
- Os resultados publicados vêm de uma única CPU, sistema operacional, JDK e
  configuração de armazenamento.
- Execuções com um fork e iterações curtas não oferecem intervalos estatísticos
  fortes.
- Evidências de NMT, async-profiler e `perf` não foram preservadas.
- Não restam números atuais publicáveis para cargas de TCP real, recovery,
  transação ou replicação.

## Reprodutibilidade

Consulte o [guia do harness de benchmarks](../benchmarks/README-ptBR.md). O
código em `src/jmh` e as anotações JMH são a fonte da verdade executável.
Arquivos temporários de resultados são deliberadamente gravados abaixo do
diretório de build ignorado.

TempoKV é licenciado sob a [Apache License 2.0](../LICENSE), identificador SPDX
`Apache-2.0`.

## Interpretação dos resultados

- Não compare throughput e latência como métricas equivalentes.
- Não compare `FsyncPolicy.NEVER` e `FsyncPolicy.ALWAYS` como se oferecessem a
  mesma durabilidade.
- Considere percentis e alocação junto com uma média ou pontuação de throughput.
- Repita as medições na máquina e no filesystem de implantação pretendidos.
- Trate a primeira execução local como baseline, não como promessa.
