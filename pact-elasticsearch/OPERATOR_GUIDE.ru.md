# Доступ к Elasticsearch через DataAccess: руководство оператора

`pact-elasticsearch` позволяет описать в Kubernetes, какие пользователи могут
работать с Elasticsearch и что именно им разрешено. В `DataAccess` можно
создать native-пользователя, выдать права на индексы и кластер, назначить
существующие роли и затем отозвать эти назначения изменением YAML.

Руководство описывает текущую реализацию, проверенную с **Elasticsearch 9.2.3**.
Для развёртывания выберите образ PACT, содержащий описанные возможности.
Примеры используют API group из Helm-чарта — `pactproject.github.io/v1` — и
настроенный backend с ID `search`. В своей установке подставьте их значения.

## Найти нужную задачу

| Нужно | Куда смотреть |
| --- | --- |
| Создать аккаунт без хранения пароля | [Аккаунты](#accounts) |
| Взять пароль из Secret или настроить ротацию | [Пароли](#passwords) |
| Читать данные или работать с ними из приложения | [Права на индексы](#indices), [готовые примеры](#recipes) |
| Писать логи без перезаписи старых документов | [Добавление документов](#append-only) |
| Работать с несколькими индексами, alias или data stream | [Выбор объектов](#targets) |
| Смотреть состояние кластера, управлять шаблонами, ILM или pipelines | [Кластерные права](#cluster) |
| Выдать доступ к Kibana | [Существующие роли и Kibana](#roles) |
| Ограничить документы, поля или Kibana Space | [Расширенные права через роли](#advanced) |
| Сохранить ручные назначения или убрать их все | [Режимы согласования](#modes) |
| Отозвать доступ или удалить DataAccess | [Отзыв доступа](#revoke) |
| Подключить приватный CA | [Настройка подключения](#connection) |
| Разобраться с ошибкой или неожиданным доступом | [Проверка и диагностика](#diagnostics) |
| Понять, какие объекты Pact сам не настраивает | [Границы управления](#boundaries) |

<a id="first"></a>
## Первый DataAccess: чтение логов

Предположим, native-пользователь `analyst` уже существует. Этот объект выдаст
ему чтение индексов с префиксом `logs-payments-` и их метаданных:

```yaml
apiVersion: pactproject.github.io/v1
kind: DataAccess
metadata:
  name: payments-log-reader
  namespace: payments
spec:
  resources:
    - search:
        index: logs-payments-*
      access:
        - users: [analyst]
          permissions:
            indices: [read, view_index_metadata]
```

Здесь `search` — **ID подключения из конфигурации PACT**, а не фиксированное
слово и не имя Elasticsearch-кластера. Например, для backend с
`id: production-search` ключом будет `production-search`.

`users` содержит точные имена пользователей Elasticsearch. Это не Kubernetes
ServiceAccount, не группа LDAP и не список ролей. Одно объявление может
выдавать одинаковые права нескольким пользователям:

```yaml
resources:
  - search:
      index: logs-payments-*
    access:
      - users: [analyst, support-reader]
        permissions:
          indices: [read, view_index_metadata]
```

**Все дальнейшие YAML-фрагменты с `resources` или `identities` вставляются
внутрь `spec` DataAccess.** Метаданные из полного примера выше повторять в
каждом фрагменте не нужно. Пользователи в примерах должны существовать либо
иметь отдельное объявление `ensure`, описанное ниже.

Pact объединяет объявления для одного пользователя. Если один DataAccess
даёт ему чтение `logs-*`, а другой — запись `orders-*`, останутся оба набора
прав. Пространство имён Kubernetes организует сами DataAccess, но **не
ограничивает** их действие одноимёнными индексами Elasticsearch. Контроллер
собирает объявления из всех наблюдаемых namespace; право менять DataAccess
следует выдавать с учётом этого.

<a id="accounts"></a>
## Аккаунты: существующий пользователь или создание нового

Backend работает с **native-пользователями Elasticsearch**. Технический
пользователь приложения — такой же native-пользователь: префикс `svc-` не
имеет специального значения и не запускает автоматическое создание.
Встроенные пользователи, например `elastic` и `kibana_system`, исключены из
управления Pact. Elasticsearch различает их и native-пользователей.
[Описание встроенных пользователей](https://www.elastic.co/docs/deploy-manage/users-roles/cluster-or-deployment-auth/built-in-users).

Если аккаунт уже есть, достаточно указать его в `access.users`. Отдельная
identity для выдачи прав ему не нужна. Pact сохраняет его `full_name`, `email`,
метаданные и состояние `enabled`: назначение роли не включает отключённую
учётную запись.

Для создания нового пользователя нужны **оба** условия:

1. У backend настроено `principal-mode: ensure`.
2. В `spec.identities` есть этот пользователь с `ensure: true`.

```yaml
identities:
  - backend: search
    name: payments-api
    ensure: true
resources:
  - search:
      index: orders-*
    access:
      - users: [payments-api]
        permissions:
          indices: [read, write]
```

`backend` в identity и ключ ресурса должны ссылаться на одно подключение.
По умолчанию `principal-mode` равен `require`, а у явной identity `ensure`
равен `true`. Для создания аккаунта всё равно нужен backend-режим `ensure`;
при `require` отсутствующий аккаунт будет ошибкой. `ensure: false` запрещает
создание для конкретной identity.

Вместо `backend: search` можно задать `backends: [search, other-search]`:
одна декларация разворачивается в отдельные identities с общими именем,
настройками и ссылкой на Secret. Одновременно задавать оба поля нельзя.

Можно создать аккаунт **без выдачи прав**:

```yaml
identities:
  - backend: search
    name: future-operator
    ensure: true
resources: []
```

Одна пара backend/имя пользователя должна иметь **одного владельца identity**
среди DataAccess. Права этому пользователю при этом могут выдавать несколько
DataAccess. Это позволяет держать создание аккаунта в одном месте, а
разрешения разных приложений — в отдельных объявлениях.

При удалении identity или всего DataAccess Pact **не удаляет пользователя**.
Созданная учётная запись также может остаться после неуспешного применения
прав. Удаление, отключение аккаунта и изменение его профиля выполняются
отдельно от Pact.

<a id="passwords"></a>
## Пароли: вручную или через Kubernetes Secret

### Без Secret

При создании аккаунта без `passwordSecretRef` Pact генерирует случайный
пароль, передаёт его Elasticsearch и не сохраняет его в статусе, Git или
логах. Получить этот пароль из Pact впоследствии нельзя.

Это подходит для процесса «создать аккаунт и настроить доступ, затем вручную
выдать пользователю пароль»: оператор меняет пароль средствами Elasticsearch.
Последующие применения DataAccess **не сбрасывают ручной пароль**. Для уже
существующего аккаунта случайный пароль вообще не устанавливается.

### Из Secret

Для приложения, которому пароль нужен сразу, можно сослаться на Secret:

```yaml
identities:
  - backend: search
    name: payments-api
    ensure: true
    passwordSecretRef:
      name: payments-search-credentials
      key: password
resources:
  - search:
      index: orders-*
    access:
      - users: [payments-api]
        permissions:
          indices: [read, write]
```

Secret должен находиться **в namespace этого DataAccess**. Создайте его своим
обычным способом, например через SealedSecrets или внешний менеджер секретов.
Открытый пароль в DataAccess не поддерживается. При создании аккаунта Pact
использует пароль из Secret; для существующего аккаунта первое назначение
такой ссылки также устанавливает пароль.

Контроллер отслеживает версию Secret. Изменение его `resourceVersion` или
источника пароля вызывает установку пароля; изменение другого ключа того же
Secret тоже меняет версию. Проверка происходит при informer resync, по
умолчанию каждые пять секунд. Это не расписание обязательной смены паролей:
без новой версии повторной установки нет.

При наличии `passwordSecretRef` источником пароля остаётся Secret: ручная
смена может быть заменена его значением при следующем обновлении версии.
Удаление ссылки прекращает управление паролем, но не меняет текущий пароль
и не отзывает выданные роли.

Для установки пароля существующему пользователю можно объявить identity с
`ensure: false` и `passwordSecretRef`; режим `require` допускает такую работу
с уже существующим аккаунтом.

Право чтения Secret выдаётся **ServiceAccount контроллера PACT**, отдельно от
доступа пользователя к Elasticsearch. Пример значений Helm:

```yaml
secretAccess:
  create: true
  grants:
    - namespace: payments
      names: [payments-search-credentials]
```

Достаточно `get` нужного Secret по имени; `list` и `watch` Secrets контроллеру
не требуются. Более подробно это описано в [руководстве Helm](../charts/pact/README.md#secret-access-for-identities).

<a id="targets"></a>
## Какие индексы, alias и data streams выбрать

В `index` указывается имя или шаблон имён, который Elasticsearch получает в
поле `names` роли. Для обычных индексных прав можно использовать имя индекса,
alias или data stream, а также wildcard вроде `logs-payments-*`.
[Правила имён и шаблонов в ролях Elasticsearch](https://www.elastic.co/docs/deploy-manage/users-roles/cluster-or-deployment-auth/role-structure).

Несколько независимых объектов можно перечислить списком:

```yaml
resources:
  - search:
      index: [orders-*, customers, application-events]
    access:
      - users: [reporting]
        permissions:
          indices: [read, view_index_metadata]
```

Pact разворачивает список в отдельные цели и объединяет права. Пустой список
не означает «все индексы»; он считается ошибкой. Шаблон, включающий `*`, лучше
заключать в кавычки, если он начинается с этого символа, например `index: "*"`.
Для кластерной цели `cluster: "*"` кавычки обязательны в приведённых примерах:
одиночная звёздочка имеет специальное значение в YAML.

Для разных прав на разные объекты создайте отдельные записи `resources`.
В одной цели нельзя смешать `index` и `cluster`, а у записи с `index` допустим
только `permissions.indices`.

Pact не проверяет наличие индекса при выдаче роли и не создаёт его. Поэтому
можно заранее разрешить `orders-*`, а сами индексы создать позже. Права на
alias и на физический индекс — разные точки доступа: проверяйте тот адрес,
который использует приложение. Имя data view из Kibana в `index` не подходит.

<a id="indices"></a>
## Права на индексы: что выбрать

`permissions.indices` — непустой список строк с **нативными именами
привилегий Elasticsearch**. Частые варианты:

| Привилегия | Назначение |
| --- | --- |
| `read` | Чтение документов и поиск. |
| `view_index_metadata` | Чтение метаданных. |
| `create_doc` | Добавление новых документов. |
| `create` | Индексация, включая перезапись документа. |
| `index` | Индексация и обновление документов. |
| `write` | Запись, обновление и удаление документов. |
| `delete` | Удаление документов. |
| `create_index` | Создание индекса. |
| `auto_configure` | Автосоздание индексов/потоков и допустимые автоматические изменения mapping. |
| `delete_index` | Удаление индекса. |
| `monitor` | Мониторинг индекса. |
| `maintenance` | Refresh, flush, force merge. |
| `manage` | Администрирование индекса. |
| `manage_ilm` | Индексные операции ILM. |
| `manage_data_stream_lifecycle` | Управление lifecycle data stream. |
| `all` | Все индексные действия в указанной области. |

Это справочник частых вариантов, а не whitelist Pact. Полное значение
привилегий определяет [Elasticsearch](https://www.elastic.co/docs/reference/elasticsearch/security-privileges).
Pact принимает другие имена из каталога вашего сервера; способ получить его
приведён [ниже](#catalog).

Есть несколько полезных различий:

- `write` не означает `read`: приложению, которое и читает, и пишет, укажите
  оба права.
- `delete` удаляет документы, `delete_index` — целый индекс.
- `manage` относится к администрированию; для работы с содержимым выбирайте
  соответствующие права на документы.
- `all` под `indices` относится к выбранным индексам, а не даёт
  административный доступ ко всему кластеру.

В Elasticsearch 9 выдача `write` или `index` сама по себе не даёт общего права
на изменение mapping. Для явного Update mapping API нужно индексное `manage`;
автоматические изменения при индексации — отдельный случай
`auto_configure`. Поэтому ошибку mapping не стоит исправлять простым
расширением `read` до `write`.
[Update mapping API](https://www.elastic.co/docs/api/doc/elasticsearch/v9/operation/operation-indices-put-mapping),
[Index API](https://www.elastic.co/docs/api/doc/elasticsearch/v9/operation/operation-index).

<a id="recipes"></a>
## Готовые наборы для приложений

### Чтение существующих индексов

```yaml
resources:
  - search:
      index: orders-*
    access:
      - users: [reporting]
        permissions:
          indices: [read, view_index_metadata]
```

`view_index_metadata` полезно клиентам, которым нужны mapping и field
capabilities; чтение самих документов выдаётся через `read`.

### Чтение и изменение документов без самостоятельного создания индексов

```yaml
resources:
  - search:
      index: orders-*
    access:
      - users: [payments-api]
        permissions:
          indices: [read, write]
```

Этот набор предполагает, что индексы и необходимые mappings подготовлены
заранее. Он включает удаление документов, но не даёт отдельного
`delete_index`.

<a id="append-only"></a>
### Сборщик логов: добавлять, не перезаписывать

```yaml
resources:
  - search:
      index: logs-payments-*
    access:
      - users: [log-writer]
        permissions:
          indices: [create_doc, auto_configure]
```

`create_doc` рассчитан на запросы с семантикой создания: например,
`PUT /logs-payments-2026.10/_create/event-123`, `op_type=create` или Bulk
операции `create`. Более широкие назначения тому же пользователю могут
снова разрешить перезапись. `auto_configure` позволяет автоматически
создавать подходящие индексы/data streams при записи; настройки кластера и
необходимые templates по-прежнему должны это позволять.
[Create document API](https://www.elastic.co/docs/api/doc/elasticsearch/v9/operation/operation-create).

Если индексы и mappings полностью готовит оператор, `auto_configure` можно
убрать. Для явного `PUT /new-index` применяется другой набор:

```yaml
resources:
  - search:
      index: orders-*
    access:
      - users: [index-provisioner]
        permissions:
          indices: [create_index]
```

Создание индекса с aliases может потребовать дополнительных прав на aliases
и индексы; этот минимальный пример не является готовым набором для любого
тела Create index API. [Требования Create index API](https://www.elastic.co/docs/api/doc/elasticsearch/v9/operation/operation-indices-create).

### Администратор выбранных индексов с доступом к данным

```yaml
resources:
  - search:
      index: orders-*
    access:
      - users: [payments-index-admin]
        permissions:
          indices: [all]
```

Область действия — `orders-*`. Для администратора, которому нужны только
операции с настройками и структурой, рассмотрите `manage` вместо `all`.

<a id="cluster"></a>
## Кластерные права

Для кластерных привилегий используется отдельная цель:

```yaml
resources:
  - search:
      cluster: "*"
    access:
      - users: [cluster-observer]
        permissions:
          cluster: [monitor]
```

`cluster: "*"` обозначает кластер, к которому подключён backend `search`.
Это не wildcard по всем подключениям Pact. Иное значение `cluster` не
поддерживается. Чтобы работать со вторым Elasticsearch, настройте второй
backend ID и отдельные объявления для него.

Ниже — ориентиры для выбора нативной привилегии. Каждая строка с несколькими
именами перечисляет варианты, а не требует выдавать их все.

| Задача | Привилегии для `permissions.cluster` |
| --- | --- |
| Мониторинг | `monitor` |
| Общее администрирование | `manage` |
| Шаблоны индексов | `manage_index_templates` |
| Ingest pipelines | `manage_ingest_pipelines` |
| Политики ILM | `read_ilm`, `manage_ilm` |
| Snapshots | `monitor_snapshot`, `create_snapshot` |
| Политики SLM | `read_slm`, `manage_slm` |
| Security | `read_security`, `manage_security` |
| API keys | `manage_own_api_key`, `manage_api_key` |
| Machine learning | `monitor_ml`, `manage_ml` |
| Transforms | `monitor_transform`, `manage_transform` |
| Enrich | `monitor_enrich`, `manage_enrich` |
| Inference | `monitor_inference`, `manage_inference` |
| Watcher | `monitor_watcher`, `manage_watcher` |
| Все кластерные права | `all` |

Для остальных подсистем и точного состава операций используйте
[каталог привилегий Elasticsearch](https://www.elastic.co/docs/reference/elasticsearch/security-privileges)
и список, возвращаемый вашим сервером. Наличие привилегии не включает саму
функцию продукта: её доступность зависит также от установки и лицензии.
Кластерное `manage` не следует считать заменой `manage_security`.

Кластерная привилегия в этой модели не имеет фильтра по имени pipeline,
template, policy или snapshot repository. Например, соседняя запись с
`index: orders-*` **не сужает** `manage_index_templates` до шаблонов для
`orders-*`. Все выбранные кластерные права выдаются в области, определяемой
самим Elasticsearch.

### Оператор шаблонов и pipelines

```yaml
resources:
  - search:
      cluster: "*"
    access:
      - users: [search-provisioner]
        permissions:
          cluster: [manage_index_templates, manage_ingest_pipelines]
```

Pact выдаст разрешения на эти API, но не создаст template или pipeline и не
даст пользователю право записи документов. Для записи нужен отдельный
индексный набор. [API шаблонов](https://www.elastic.co/docs/api/doc/elasticsearch/v9/operation/operation-indices-put-index-template),
[API pipelines](https://www.elastic.co/docs/api/doc/elasticsearch/v9/operation/operation-ingest-put-pipeline).

### Оператор ILM

```yaml
resources:
  - search:
      cluster: "*"
    access:
      - users: [lifecycle-operator]
        permissions:
          cluster: [manage_ilm]
  - search:
      index: orders-*
    access:
      - users: [lifecycle-operator]
        permissions:
          indices: [manage_ilm]
```

Одинаковое имя `manage_ilm` относится к двум областям. Этот пример выдаёт
управление политиками и индексные ILM-права, но не обещает полный набор для
любой lifecycle-политики: её действия могут требовать других индексных прав.
Сами политики настраиваются отдельно.
[Создание и обновление ILM policy](https://www.elastic.co/docs/api/doc/elasticsearch/v9/operation/operation-ilm-put-lifecycle).

### Создание snapshots

```yaml
resources:
  - search:
      cluster: "*"
    access:
      - users: [backup-runner]
        permissions:
          cluster: [create_snapshot]
```

Это разрешение на создание snapshots, а не декларация репозитория резервного
копирования и не ограничение snapshots шаблоном из `index`. Настройка
repository, восстановление и управление SLM — отдельные операции со своими
требованиями. [Create snapshot API](https://www.elastic.co/docs/api/doc/elasticsearch/v9/operation/operation-snapshot-create).

<a id="roles"></a>
## Существующие роли: встроенные и собственные

Если подходящая роль уже есть в Elasticsearch, можно управлять только её
назначением:

```yaml
resources:
  - search:
      cluster: "*"
    access:
      - users: [analyst]
        roles: [payments-reader]
```

`roles` — непустой список точных имён. Роль должна существовать и быть
доступной через `GET /_security/role/payments-reader`. Pact не создаёт её из
имени и не изменяет её определение. Неизвестная роль останавливает
применение. Генерируемые самим Pact роли с его меткой владения нельзя
подключать таким способом: соответствующие права объявляйте через
`permissions`.

Назначения пишутся только на `cluster: "*"`, даже если роль содержит
исключительно индексные или Kibana-права. Указание `roles` на `index`
не поддерживается. При этом `roles` можно сочетать с `permissions.cluster`
в той же записи и с индексными правами в других записях.

При использовании только `roles` Pact не создаёт пустую дополнительную роль.
Если описание существующей роли впоследствии изменится, доступ её
пользователей изменится средствами Elasticsearch, без правки DataAccess.

### Kibana

Для доступа к данным и функциям Kibana можно сочетать индексные права и
встроенную роль:

```yaml
resources:
  - search:
      index: logs-payments-*
    access:
      - users: [analyst]
        permissions:
          indices: [read, view_index_metadata]
  - search:
      cluster: "*"
    access:
      - users: [analyst]
        roles: [kibana_admin]
```

`kibana_admin` открывает все функции Kibana во всех spaces. Для более узкого
доступа создайте роль нужного Space/функций в Kibana и назначайте её имя,
например `payments-kibana-reader`, через `roles`. Отдельных полей `space`,
`dashboard` или `feature` в DataAccess этого backend нет.

Другие полезные встроенные роли: `monitoring_user`, `snapshot_user`,
`ingest_admin`, `machine_learning_user`, `transform_user`. `viewer` и `editor`
также дают права на данные и могут оказаться шире выбранного шаблона
индексов. `superuser` предоставляет крайне широкие полномочия. Системные
роли вроде `kibana_system` предназначены для компонентов Elastic Stack, а не
как общий шаблон доступа человека.
Состав встроенных ролей проверяйте в [справочнике Elastic](https://www.elastic.co/docs/reference/elasticsearch/roles).

<a id="advanced"></a>
## Расширенные права: документы, поля, impersonation, приложения

Pact умеет **назначать** существующие роли с возможностями, для которых у
DataAccess нет отдельных полей:

| Нужно | Как оформить |
| --- | --- |
| Document-level security: читать только определённые документы | Создать роль с DLS отдельно; назначить её через `roles`. |
| Field-level security: читать только выбранные поля | Создать роль с FLS отдельно; назначить её через `roles`. |
| Отдельный Kibana Space и набор функций | Подготовить роль в Kibana; указать её имя. |
| `run_as`, impersonation | Подготовить роль с `run_as`; указать её имя. |
| Application privileges | Подготовить роль с `applications`; указать её имя. |
| Remote index/cluster privileges | Подготовить роль с нужной структурой; указать её имя. |
| Особые параметры вроде `allow_restricted_indices` | Подготовить роль с этими параметрами; указать её имя. |

Это назначение всей роли, а не выбор отдельных частей её определения.
Подключения к удалённым кластерам, API keys и определения application
privileges создаются отдельно. Структура таких ролей описана в
[Role structure](https://www.elastic.co/docs/deploy-manage/users-roles/cluster-or-deployment-auth/role-structure).

Например, если роль `payments-dls-reader` уже ограничивает чтение нужным
подмножеством документов:

```yaml
resources:
  - search:
      cluster: "*"
    access:
      - users: [payments-auditor]
        roles: [payments-dls-reader]
```

Не добавляйте этому пользователю обычное неограниченное `read` на тот же
индекс в надежде «дополнить» DLS. Elasticsearch объединяет разрешения ролей;
роль с полным чтением может снять ожидаемое ограничение документов/полей.
То же относится к ролям, оставшимся от ручных назначений.
[Совместное действие DLS/FLS и нескольких ролей](https://www.elastic.co/docs/deploy-manage/users-roles/cluster-or-deployment-auth/controlling-access-at-document-field-level).

<a id="modes"></a>
## Сохранять ручные назначения или управлять доступом целиком

Режим задаётся **в конфигурации backend**, не в DataAccess. Нельзя одному
DataAccess выбрать `managed`, а другому `authoritative` для одного и того же
подключения.

| Режим | Что происходит с ролями пользователя |
| --- | --- |
| `managed` — явный режим совместимости | Pact согласует свои прямые привилегии и явно объявленные назначения `roles`. Остальные роли сохраняются. |
| `authoritative` — по умолчанию | У пользователя остаются ровно объявленные `roles` и роль Pact с прямыми привилегиями, если они есть. Все остальные назначения снимаются. |

Пример: у `analyst` уже есть вручную назначенная `legacy-admin`, а DataAccess
объявляет чтение `logs-*` и `roles: [monitoring_user]`.

- В `managed` останутся `legacy-admin`, `monitoring_user` и роль Pact с
  чтением `logs-*`. Широкие полномочия `legacy-admin` продолжат действовать.
- В `authoritative` останутся `monitoring_user` и роль Pact. Назначение
  `legacy-admin` снимется, но сама роль и назначения другим пользователям
  останутся.

Область `authoritative` — пользователи из **предыдущих и новых access и
identities этого backend**. Другие пользователи кластера не затрагиваются.
Однако identity без grants тоже включает пользователя в эту область: в
`authoritative` объявление только identity означает пустой объявленный набор
ролей и может снять его текущие роли.

Смешанная роль снимается целиком. Если она одновременно даёт чтение индексов,
администрирование кластера и доступ к Kibana, нельзя удалить только одну её
часть через DataAccess. Само определение общей роли Pact не редактирует.

Не направляйте два независимых контроллера/подключения с `authoritative` на
одних пользователей: они будут задавать конкурирующие наборы назначений.
Аккаунт, которым Pact подключается к Security API, держите вне области
управления его же правами.

<a id="revoke"></a>
## Как отозвать доступ

Уберите нужное имя пользователя, привилегию или роль из объявления либо
удалите соответствующую запись ресурса. Для полного удаления объявления
можно удалить DataAccess:

```sh
kubectl delete dataaccess payments-log-reader -n payments
```

Пустые `permissions.indices: []`, `permissions.cluster: []` и `roles: []`
не являются командами отзыва: backend требует непустые наборы. Удаляйте само
поле/запись. Если в записи нет ни `permissions`, ни `roles`, удалите её целиком.
Чтобы оставить только создание аккаунта, сохраните identity и поставьте
`resources: []`, учитывая область `authoritative`.

Pact пересчитает объединение оставшихся DataAccess. Если тот же доступ
объявлен ещё где-то, он сохранится. Если прямых привилегий больше нет,
назначение генерируемой роли Pact снимается, а эта роль удаляется.

В `managed` роль из `roles`, которую раньше объявлял Pact, снимается при
удалении её из желаемого состояния. Это относится и к случаю, когда до
первого объявления её вручную назначил кто-то другой: после включения в
DataAccess это назначение участвует в управлении Pact. Другие ручные роли
сохраняются. В `authoritative` набор назначений согласуется полностью.

Удаление последнего DataAccess не удаляет аккаунт, не сбрасывает его пароль,
не удаляет общие роли и не запускает отзыв API keys. Встроенные базовые
возможности пользователя Elasticsearch, например проверка собственной
аутентификации, не исчезают от пустого набора явно назначенных ролей.
[Default role в Elasticsearch](https://www.elastic.co/docs/reference/elasticsearch/roles).

Контроллер использует finalizer и завершает удаление DataAccess после
успешной очистки. Если Elasticsearch недоступен, объект может оставаться с
`deletionTimestamp` до успешного завершения. Снятие finalizer вручную
обходит эту гарантию очистки.

<a id="connection"></a>
## Что оператор платформы настраивает один раз

Автор DataAccess выбирает пользователей, цели и права. Адрес Elasticsearch,
авторизация самого Pact и режим согласования задаются в его конфигурации:

```yaml
backends:
  - id: search
    type: elasticsearch
    config:
      endpoint: https://elasticsearch.example:9200
      username: pact
      password: ${ES_PASSWORD}
      principal-mode: ensure
      reconciliation-mode: authoritative
      ca-certificate: /etc/pact-ca/elasticsearch/ca.crt
```

Этот фрагмент относится к конфигурации **PACT**, а не к `spec` DataAccess.
В Helm список находится в `pact.backends`. Вместо пары `username`/`password`
можно настроить `api-key` с encoded API key Elasticsearch; одновременно
оба способа не задаются.

| Параметр | Значение по умолчанию / смысл |
| --- | --- |
| `endpoint` | Обязательный HTTP(S) URL; допустим базовый путь reverse proxy. |
| `principal-mode` | `require`: аккаунты должны существовать. `ensure` разрешает явно запрошенное создание. |
| `reconciliation-mode` | `authoritative`: точные объявленные назначения в области пользователей. Явный `managed` сохраняет посторонние назначения. |
| `ca-certificate` | Не задан: использовать стандартное доверие JVM. Задан: PEM bundle для HTTPS этого backend. |

Соединяющемуся аккаунту нужны Security API-права для чтения пользователей,
ролей и каталога привилегий, изменения собственных ролей Pact и назначений,
а при необходимости — создания пользователей и смены паролей.
`manage_security` покрывает эти операции. Это отдельный административный
аккаунт, его пароль не связан с паролями пользователей из identities.
[Security API управления пользователями](https://www.elastic.co/docs/api/doc/elasticsearch/v9/operation/operation-security-put-user).

Для приватного CA смонтируйте PEM из существующего ConfigMap или Secret:

```yaml
extraVolumes:
  - name: elasticsearch-ca
    configMap:
      name: elasticsearch-ca
extraVolumeMounts:
  - name: elasticsearch-ca
    mountPath: /etc/pact-ca/elasticsearch
    readOnly: true
extraEnv:
  - name: ES_PASSWORD
    valueFrom:
      secretKeyRef:
        name: pact-elasticsearch-admin
        key: password
```

Это значения Helm-чарта. ConfigMap `elasticsearch-ca` должен содержать ключ
`ca.crt`. Пример подключения и томов целиком:
[`examples/elasticsearch-ca.yaml`](../charts/pact/examples/elasticsearch-ca.yaml).
Проверка hostname остаётся включённой; сертификат должен соответствовать
имени из `endpoint`. Изменение CA-файла подхватывается после перезапуска
контроллера. Если задан `ca-certificate`, доверие этого клиента строится по
указанному bundle; включите туда все нужные CA.

Для общего JVM truststore чарт также поддерживает `extraInitContainers`,
`extraVolumes`, `extraVolumeMounts` и `JAVA_TOOL_OPTIONS` через `extraEnv`.
Для одного Elasticsearch обычно достаточно PEM без отдельного truststore.

<a id="catalog"></a>
## Как узнать все доступные привилегии

В Pact нет отдельного закрытого списка из нескольких разрешений. Полные
поддерживаемые сервером имена можно получить в Elasticsearch Dev Tools:

```http
GET /_security/privilege/_builtin
```

В ответе интересны массивы:

- `index` → имена для `permissions.indices`;
- `cluster` → имена для `permissions.cluster`.

Обратите внимание на разные имена: в ответе API — `index`, в DataAccess —
`indices`. Остальные категории ответа не превращаются автоматически в
новые поля DataAccess. Если нужное право есть в подходящем массиве, его
можно указать тем же способом, что `read` или `monitor`; смысл и ограничения
определяет сервер. В каталоге встречаются также служебные и устаревшие
привилегии: наличие имени не отменяет ограничений на его назначение обычной
пользовательской роли. Перед выдачей редкого права проверьте его описание в
[справочнике Elasticsearch](https://www.elastic.co/docs/reference/elasticsearch/security-privileges).
[Get builtin privileges API](https://www.elastic.co/docs/api/doc/elasticsearch/v9/operation/operation-security-get-builtin-privileges).

Доступные определения ролей смотрят отдельно:

```http
GET /_security/role
GET /_security/role/payments-reader
```

<a id="diagnostics"></a>
## Применение, проверка и типичные ошибки

Примените объект и посмотрите его статус:

```sh
kubectl apply -f payments-log-reader.yaml
kubectl get dataaccess payments-log-reader -n payments -o yaml
kubectl logs -n pact-system deployment/pact
```

Имя Deployment зависит от Helm release. У успешно обработанного объекта
будет `status.phase: Ready`, а `status.observedGeneration` — поколение
обработанного spec. При ошибке смотрите `status.lastError`. `Ready` означает
успешное применение объявления; проверку конкретного клиентского запроса
лучше делать под тем пользователем, для которого выданы права.

Чтобы проверить ожидаемые привилегии, выполните **от имени этого
пользователя**:

```http
POST /_security/user/_has_privileges
{
  "cluster": ["monitor"],
  "index": [
    {
      "names": ["logs-payments-2026.10"],
      "privileges": ["read", "write"]
    }
  ]
}
```

API вернёт отдельные результаты и `has_all_requested`; HTTP 200 сам по себе
не означает, что все запрошенные права есть. Эта проверка показывает
совокупные привилегии пользователя. Для проверки DLS/FLS дополнительно
выполните реальные запросы к ожидаемым документам и полям.
[Check user privileges API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-has-privileges-3).

| Симптом | Что проверить |
| --- | --- |
| Пользователь не найден | Точное native-имя; `principal-mode: ensure` и соответствующий `identity.ensure: true`, если требуется создание. |
| Неизвестная роль | Результат `GET /_security/role/<имя>`; Pact не создаёт роль по записи в `roles`. |
| Неизвестная привилегия | Каталог сервера и правильный раздел `indices`/`cluster`; права не переводятся из названий других бэкендов. |
| Ошибка структуры | Для `index` используйте только `permissions.indices`; для `cluster: "*"` — `permissions.cluster` и/или `roles`; списки должны быть непустыми. |
| Secret не читается | Его namespace, имя и ключ; RBAC ServiceAccount контроллера на `get` этого Secret. |
| Identity объявлена дважды | Для одной пары backend/пользователь оставьте identity в одном DataAccess. |
| Коллизия генерируемой роли | Роль с ожидаемым именем уже существует без метаданных владения Pact. Pact не перезапишет её. |
| HTTPS-запросы не проходят | Доверенный CA, доступность PEM-файла в контейнере, SAN сертификата и имя в `endpoint`; детали соединения смотрите в логах. |
| После отзыва пользователь всё ещё читает данные | Другие DataAccess и роли, сохранённые в `managed`; широкие роли или `all`; способ аутентификации клиента. |
| Нужен append-only, но документы перезаписываются | Более широкая роль этого пользователя; используются ли операции `create`; не подменён ли `create_doc` на `create`. |
| Пользователь создан, но не может войти | Установлен ли известный пароль; был ли аккаунт отключён до применения; достаточно ли только выдачи роли для вашего способа входа. |
| DataAccess не удаляется | Доступность backend и ошибки очистки перед снятием finalizer. |

Отсутствующая роль, неизвестная привилегия и обнаруженные ошибки объявлений
проверяются до изменений прав. Если запись всё же завершится ошибкой в
процессе применения, Pact пытается компенсировать уже выполненные изменения
ролей и назначений. Создание аккаунта и успешную смену пароля компенсировать
нельзя. Не рассматривайте изменение нескольких бэкендов как атомарную
транзакцию.

### Ручные изменения и периодическая проверка

Текущий контроллер применяет изменённый spec и реагирует на новые версии
Secret. Он не является постоянным сканером Elasticsearch: неизменный
DataAccess не гарантирует автоматический откат любой ручной правки ролей
каждые пять секунд. Изменение только annotations/статуса и повторный
`kubectl apply` без изменения spec также не являются командой принудительного
применения. Согласуйте одного владельца изменений прав и отдельно проверяйте
фактический доступ.

После устранения причины **неуспешного применения нового spec** можно
перезапустить контроллер, чтобы он снова обработал объекты:

```sh
kubectl rollout restart deployment/pact -n pact-system
```

При запуске контроллер восстанавливает последнее успешно применённое
состояние из статуса и обрабатывает текущие spec. Это позволяет повторить
незавершённое изменение; такой перезапуск не является универсальным
принудительным исправлением drift у уже успешно применённого и неизменного
состояния.

<a id="boundaries"></a>
## Что именно находится под управлением Pact

| Возможность | Поддержка через DataAccess |
| --- | --- |
| Создать native-пользователя | Да, через identity и разрешающий режим backend. |
| Установить/ротировать пароль | Да, через `passwordSecretRef`; без него — случайный пароль только при создании. |
| Индексные и кластерные привилегии | Да, именами из соответствующего каталога сервера. |
| Назначить/снять существующую роль | Да, через `roles`. |
| Удалить лишние назначения целиком | Да, в `authoritative`, в объявленной области пользователей. |
| Создать или отредактировать общую пользовательскую роль | Нет; Pact создаёт только свои служебные роли из прямых привилегий. |
| Объявить DLS/FLS, `run_as`, application/remote privileges как отдельные поля | Нет; можно назначить заранее подготовленную роль с ними. |
| Создать индекс, data stream, alias, template, pipeline, ILM policy, snapshot | Нет; можно выдать право на соответствующие операции. |
| Создать Elasticsearch service account/token или API key | Нет; это не native identity. Можно выдать пользователю соответствующие кластерные права, где они поддерживаются сервером. |
| Настроить LDAP/SAML/OIDC role mappings и группы | Нет. |
| Управлять встроенными пользователями, профилем, `enabled` или удалять аккаунты | Нет. |
| Запретить действие поверх другой разрешающей роли | Нет: отдельной deny-модели в DataAccess нет. |
| Постоянно исправлять ручной drift без изменения желаемого состояния | Нет, это не текущая модель запуска согласования. |

Техническое описание backend и его настройки:
[README pact-elasticsearch](README.md).
