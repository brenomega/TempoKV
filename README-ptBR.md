If you want to read the documentation in English, visit: [README.md](README.md).

# TempoKV

TempoKV é um banco de dados chave-valor temporal que mantém um histórico de
versões imutável. Ele oferece leituras atuais, leituras históricas, comparações
e restauração por meio da criação de um novo commit, sem reescrever o histórico
anterior.

## O problema

Armazenamentos chave-valor convencionais priorizam o estado mais recente.
Sistemas operacionais também podem precisar descobrir qual era o valor em uma
versão ou instante anterior, o que mudou entre dois pontos e como restaurar um
valor anterior sem perder a trilha de auditoria.

## O que o TempoKV oferece

- histórico imutável por chave sobre um mecanismo de armazenamento MVCC;
- operações `GETAT`, `HISTORY`, `DIFF` e `RESTOREAT` somente anexável;
- RESP2 e uma linguagem SQL delimitada sobre o mesmo caminho de comandos e
  armazenamento;
- transações por sessão com leituras por snapshot e detecção de conflitos de
  escrita;
- persistência WAL opcional, snapshots validados, recuperação, expiração TTL e
  compactação conservadora do WAL;
- ACLs autenticadas por comando e prefixo e replicação de primário para réplica.

## Arquitetura em resumo

RESP e SQL são adaptadores de protocolo. Ambos produzem comandos neutros em
relação ao protocolo, que passam por autorização, validação, handlers da
aplicação, coordenador de commits e mecanismo de armazenamento MVCC. Quando a
persistência está habilitada, os commits chegam ao WAL antes de ficarem
visíveis; snapshots e replay do WAL reconstroem o estado retido.

Consulte o [diagrama de classes conceitual](docs/class-diagram-ptBR.md) e os
[casos de uso](docs/use-cases-ptBR.md) para a visão completa.

## Execução rápida

TempoKV requer JDK 25. Gere o JAR executável:

```bash
./gradlew clean build
```

Inicie um nó restrito ao loopback com autenticação explicitamente desabilitada:

```bash
java -jar build/libs/tempokv-0.1.0.jar \
  --data-dir=./data/quickstart \
  --authentication-enabled=false
```

Em outro terminal:

```bash
redis-cli -p 6379 PING
```

Saída esperada:

```text
PONG
```

Continue pelo [tutorial de primeiro uso](docs/getting-started-ptBR.md) ou vá
diretamente ao [livro de receitas de comandos](docs/command-cookbook-ptBR.md).

## Demonstração

<!-- TODO: adicionar o GIF de demonstração em docs/assets/demo.gif -->

## Documentação

- [Tutorial de primeiro uso](docs/getting-started-ptBR.md)
- [Livro de receitas de comandos](docs/command-cookbook-ptBR.md)
- [Casos de uso](docs/use-cases-ptBR.md)
- [Diagrama de classes conceitual](docs/class-diagram-ptBR.md)
- [Resultados de performance, profiling e benchmarks](docs/performance-ptBR.md)
- [Guia da infraestrutura de benchmarks](benchmarks/README-ptBR.md)

## Build e testes

```bash
./gradlew check
```

`check` executa testes unitários, verificações de concorrência, testes de
integração, geração do lexer/parser SQL e relatório JaCoCo combinado.

## Estado do projeto

TempoKV é um projeto técnico finalizado e uma implementação de referência, não
uma alegação de prontidão para produção. TLS nativo não está implementado:
mantenha os endpoints no loopback ou em uma rede privada confiável, ou
posicione-os atrás de proxy, túnel ou service mesh que termine TLS. Transporte
sem criptografia fora do loopback exige consentimento explícito na
configuração.

## Licença

TempoKV é licenciado sob a [Apache License 2.0](LICENSE).
