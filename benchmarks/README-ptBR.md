[English](README.md) | **Português (Brasil)**

# Harness de Benchmarks do TempoKV

## Requisitos

- JDK 25; o toolchain do Gradle baixa ou seleciona o JDK configurado quando
  disponível;
- o Gradle Wrapper do repositório (`./gradlew`);
- memória livre suficiente para o dataset e o heap do fork selecionados.

A maioria das classes de benchmark define `-Xms512m -Xmx512m`.
`HistoricalLookupBenchmark` define `-Xms768m -Xmx768m` para o caso de 100.000
versões. Não há um requisito mínimo geral de RAM verificado. Docker não é usado
pelo harness JMH.

Compile as fontes de produção, SQL gerado e JMH com:

```bash
./gradlew jmhClasses
```

## Inventário de benchmarks

| Classe | Finalidade | Parâmetros importantes |
| --- | --- | --- |
| `StorageReadBenchmark` | Leituras atuais, históricas, páginas de histórico e `DIFF` | `datasetSize=100,1000,10000`; `historyDepth=10,100,1000` |
| `StorageWriteBenchmark` | Mutações, commits, append, restauração e GC de histórico | `datasetSize=100,1000,10000`; `historyDepth=10,100,1000` |
| `HistoricalLookupBenchmark` | Escalabilidade focada dos checkpoints esparsos | `depth=1000,10000,100000` |
| `ProtocolBenchmark` | Caminhos RESP e SQL em processo | `pipelineSize=1,16,128` |
| `NetworkProtocolBenchmark` | Endpoints RESP/SQL reais em loopback | `pipelineSize=1,16,128`; métodos fixos com `@Threads(2)`, `@Threads(4)` e `@Threads(8)` |
| `PersistenceBenchmark` | Encode/append do WAL e escrita de snapshot | `policy=NEVER,ALWAYS` |
| `PersistenceLifecycleBenchmark` | Replay, recovery, rotação e compactação | `records=100,1000` para replay e recovery |
| `TransactionBenchmark` | Ciclo de vida, conflitos e concorrência de transações | métodos fixos com `@Threads(2)`, `@Threads(4)` e `@Threads(8)` |
| `ReplicationBenchmark` | Commit primário com e sem ACK de réplica | sem `@Param` |
| `ReplicationDataBenchmark` | Aplicação, instalação de snapshot completo e catch-up incremental | sem `@Param` |

`BenchmarkFixtures` fornece dados determinísticos de setup e não é uma classe
de benchmark.

## Execução smoke rápida

Este é o menor comando verificado para compilar e executar um benchmark
pequeno:

```bash
./gradlew jmh \
  -PjmhArgs='HistoricalLookupBenchmark.currentVersion -p depth=1000 -wi 0 -i 1 -r 200ms -f 1'
```

Execuções smoke verificam o harness; elas não são evidência de desempenho. Zero
aquecimento, uma iteração curta e um fork são deliberadamente insuficientes
para publicar uma pontuação.

## Executando benchmarks selecionados

Execute uma leitura do armazenamento atual:

```bash
./gradlew jmh \
  -PjmhArgs='StorageReadBenchmark.currentGetHit -p datasetSize=10000 -p historyDepth=1000 -wi 2 -i 3 -f 1'
```

Execute operações históricas selecionadas:

```bash
./gradlew jmh \
  -PjmhArgs='HistoricalLookupBenchmark.(middleVersion|oldestTimestamp|diffDistant) -p depth=10000 -wi 2 -i 4 -f 1'
```

Execute um pipeline RESP em processo:

```bash
./gradlew jmh \
  -PjmhArgs='ProtocolBenchmark.respGetPipeline -p pipelineSize=16 -wi 2 -i 3 -f 1'
```

Execute o endpoint RESP real em loopback:

```bash
./gradlew jmh \
  -PjmhArgs='NetworkProtocolBenchmark.respPipeline -p pipelineSize=16 -wi 2 -i 3 -f 1'
```

Execute append do WAL com política de durabilidade explícita:

```bash
./gradlew jmh \
  -PjmhArgs='PersistenceBenchmark.walAppend -p policy=ALWAYS -wi 2 -i 4 -f 1'
```

Execute recovery de snapshot mais WAL:

```bash
./gradlew jmh \
  -PjmhArgs='PersistenceLifecycleBenchmark.recoverySnapshotAndWal -p records=1000 -wi 1 -i 3 -f 1'
```

Execute uma carga de transação:

```bash
./gradlew jmh \
  -PjmhArgs='TransactionBenchmark.singleKeyCommit -wi 2 -i 4 -f 1'
```

Execute commit primário mais acknowledgement da réplica:

```bash
./gradlew jmh \
  -PjmhArgs='ReplicationBenchmark.primaryCommitAndReplicaAck -wi 1 -i 3 -f 1'
```

O texto passado a `-PjmhArgs` é separado em argumentos JMH pela tarefa Gradle.
Use uma única propriedade Gradle entre aspas exatamente como mostrado. A
seleção de benchmarks do JMH é uma expressão regular.

## Executando uma medição mais longa

Este exemplo mede a classe focada de histórico com aquecimento adicional, dois
forks, o profiler de GC e saída JSON:

```bash
./gradlew jmh \
  -PjmhArgs='HistoricalLookupBenchmark.* -p depth=10000 -wi 3 -w 1s -i 5 -r 1s -f 2 -prof gc -rf json -rff build/benchmarks/history.json'
```

`HistoricalLookupBenchmark` fornece o heap fixo de 768 MiB por meio de sua
anotação `@Fork`. As outras classes de benchmark fornecem heap fixo de 512 MiB.
Registre a anotação e toda sobrescrita pela linha de comando ao comparar
resultados.

Sem `-PjmhArgs`, a tarefa Gradle usa duas iterações de aquecimento, três
iterações de medição, um fork e grava JSON em
`build/benchmarks/jmh-result.json`. As anotações das classes ainda determinam o
modo do benchmark e o heap.

## Profiling com JFR

JDK 25 e JMH 1.37 fornecem o profiler JFR. Uma execução diagnóstica curta pode
ser iniciada com:

```bash
./gradlew jmh \
  -PjmhArgs='HistoricalLookupBenchmark.middleVersion -p depth=10000 -wi 1 -i 2 -r 1s -f 1 -prof jfr:dir=build/benchmarks/jfr'
```

O JMH grava a recording abaixo de `build/benchmarks/jfr`. Todo o diretório de
build é ignorado pelo Git. Uma amostra JFR ajuda a localizar atividade de CPU,
alocação, locks e I/O; ela não substitui um benchmark antes/depois comparável.
Nunca faça commit de uma recording `.jfr`.

## Cuidados com persistência

- Use somente diretórios descartáveis criados pelo benchmark.
- Nunca aponte um benchmark para um diretório de dados de produção ou que
  contenha dados valiosos.
- Registre se o diretório temporário usa tmpfs ou filesystem persistente.
- Registre o tipo do filesystem, opções de montagem relevantes à durabilidade e
  estado do cache.
- Registre `FsyncPolicy` para todo resultado de WAL.
- Não compare `NEVER` e `ALWAYS` como durabilidades equivalentes.
- Prefira `./gradlew clean` para remover saídas de build do repositório.
  Confira o alvo exato antes de excluir qualquer diretório de benchmark
  configurado separadamente.

## Comparando resultados

Antes de interpretar dois arquivos de resultado como comparação antes/depois,
mantenha constantes:

- commit do código-fonte, exceto pela mudança intencional em teste;
- fornecedor e versão do JDK;
- heap e demais argumentos da JVM;
- parâmetros do benchmark e dataset;
- filesystem e política de diretório temporário;
- durabilidade e política de fsync;
- concorrência e quantidade de threads JMH;
- aquecimento, tempo de medição, iterações e forks;
- configuração do profiler.

Compare a mesma métrica e unidade. Inclua valores absolutos, percentis para
cargas sample-time, alocação quando disponível e a direção da mudança. Não
infira regressão ou melhoria a partir de uma única amostra ruidosa de
filesystem.

## Saída e limpeza

O resultado padrão é `build/benchmarks/jmh-result.json`. Os comandos deste guia
colocam saídas JSON e JFR abaixo de `build/benchmarks`. Bancos criados pelos
benchmarks usam diretórios temporários JMH e são removidos pelos métodos de
teardown.

O repositório ignora `build/`, `.gradle/`, logs e `data/`; portanto, arquivos de
resultado documentados, recordings do profiler, classes geradas e bancos
temporários não são rastreados. Confira `git status --short` ao terminar uma
execução. Não remova as fontes em `src/jmh`.

## Adicionando um benchmark

- Mantenha a construção do dataset e a criação de arquivos em `@Setup`, fora do
  método medido.
- Consuma saídas pelo valor retornado ou por `Blackhole`.
- Use seeds, timestamps, chaves e payloads determinísticos.
- Mantenha conjuntos de `@Param` pequenos o bastante para uma seleção smoke;
  documente tamanhos locais estendidos separadamente.
- Não adicione assertions de tempo à suíte normal de testes.
- Não adicione comportamento específico de benchmark ao código de produção.
- Use teardown para fechar sockets e remover somente o estado descartável
  criado pelo benchmark.

Para metodologia, resultados revisados e limitações, consulte
[Desempenho, Profiling e Benchmarks](../docs/performance-ptBR.md). TempoKV usa a
[Apache License 2.0](../LICENSE), identificador SPDX `Apache-2.0`.
