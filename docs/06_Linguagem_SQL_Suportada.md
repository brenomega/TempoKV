# TempoKV SQL suportado na etapa 6

O endpoint SQL textual escuta todas as interfaces em `--sql-port` (padrão
`6380`). Cada instrução deve terminar em `;`. A conexão aceita instruções
fragmentadas e várias instruções em pipeline; cada resposta termina com uma
linha em branco.

JFlex gera `TempoLexer` a partir de
`src/main/jflex/io/tempokv/protocol/sql/TempoLexer.flex`. Java CUP gera
`TempoParser` e `SqlSymbols` a partir de
`src/main/cup/io/tempokv/protocol/sql/TempoParser.cup`. As tarefas de geração
são dependências de `compileJava`, e os fontes gerados permanecem em `build/`,
sem edição manual.

## Modelo e limites

Existe uma única tabela lógica, `tempokv`, com as colunas de estado atual
`key` e `value`. Consultas atuais e `AS OF` exigem `WHERE key = '...'`; o
planner rejeita full scans. Literais SQL são UTF-8, apóstrofos são escapados
como `''`, identificadores e palavras-chave não diferenciam maiúsculas de
minúsculas.

Instruções e respostas são limitadas: cada instrução pode ter até 1 MiB,
valores seguem o limite comum de 16 MiB, e uma origem `HISTORY` examina no
máximo 1.000 versões retidas. `LIMIT` deve estar entre 1 e 1.000.

## Instruções

Estado atual:

```sql
UPSERT INTO tempokv (key, value) VALUES ('profile', 'Ada');
SELECT key, value FROM tempokv WHERE key = 'profile';
DELETE FROM tempokv WHERE key = 'profile';
```

Leitura histórica por versão ou instante:

```sql
SELECT value FROM tempokv AS OF VERSION 1 WHERE key = 'profile';
SELECT value FROM tempokv AS OF TIMESTAMP '2026-01-01T00:00:00Z'
WHERE key = 'profile';
```

Histórico, projeção, filtro, ordenação e paginação:

```sql
HISTORY 'profile' LIMIT 100 OFFSET 0;

SELECT version, committed_at, state, value
FROM HISTORY('profile')
WHERE version >= 1
ORDER BY version DESC
LIMIT 100 OFFSET 0;
```

Comparação e restauração append-only:

```sql
DIFF 'profile' BETWEEN VERSION 1 AND VERSION 2;
DIFF 'profile'
BETWEEN TIMESTAMP '2026-01-01T00:00:00Z'
AND TIMESTAMP '2026-01-02T00:00:00Z';
RESTORE 'profile' TO VERSION 1;
```

`BEGIN`, `COMMIT` e `ROLLBACK` já pertencem à gramática e produzem AST tipada,
mas a análise semântica informa que sua execução depende do gerenciador de
transações da E7.

## Semântica comum com RESP

O SQL não acessa índices, mapas, WAL ou snapshots. `SqlPlanner` produz
`KeyValueCommand` ou `TemporalCommand`; `PlanExecutor` aplica autorização e usa
o mesmo `CommandDispatcher` dos clientes RESP. Assim:

- `UPSERT` equivale a `SET`;
- `SELECT` atual equivale a `GET`;
- `DELETE` equivale a `DEL`;
- `AS OF`, `HISTORY`, `DIFF` e `RESTORE` usam os handlers de `GETAT`,
  `HISTORY`, `DIFF` e `RESTOREAT`;
- mutações confirmadas passam pelo mesmo `CommitCoordinator`, WAL e política
  de `fsync`.

## Respostas

Sucessos usam uma tabela TSV em UTF-8. A primeira linha contém as colunas e a
linha em branco encerra a resposta. `\N` representa SQL nulo; tab, quebra de
linha, retorno e barra invertida são escapados. Bytes que não formam UTF-8
válido usam o prefixo `base64:`.

```text
key	value
profile	Ada

```

Erros mantêm fases distintas:

```text
ERROR	LEXICAL	1:8	unexpected character '@'

ERROR	SYNTAX	1:18	unexpected token ';'

ERROR	SEMANTIC	-	point lookup requires WHERE key = '...'

```

As fases possíveis são `LEXICAL`, `SYNTAX`, `SEMANTIC`, `PLANNING` e
`EXECUTION`. Erros léxicos e sintáticos incluem linha e coluna quando há um
token de origem.

## Uso local

Uma sessão simples pode ser aberta com `nc`:

```bash
printf "SELECT value FROM tempokv WHERE key = 'profile';" | nc 127.0.0.1 6380
```
