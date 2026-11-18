# Aurora Writer / Reader maintenance routing sample

Spring Boot单实例应用的参考实现。Parameter Store中的值为`READ_WRITE`或`READ_ONLY`。

## 切换语义

### READ_WRITE -> READ_ONLY

1. 轮询发现Parameter为`READ_ONLY`。
2. 状态变为`DRAINING`，新的可写Spring事务立即失败。
3. 已经开始的可写事务继续执行，直到commit或rollback。
4. 活跃写事务数变为0后，把新连接路由到Reader。
5. 状态变为`READY`，此时才认为切换成功。

### READ_ONLY -> READ_WRITE

1. 轮询发现Parameter为`READ_WRITE`。
2. 对Writer执行`SELECT 1`。
3. 健康检查成功后，把新连接路由到Writer并开放写事务。
4. 失败时保持Reader并进入`ERROR`，不会自动开放写入。

## 使用前必须修改

- `application.yml`中的Writer和Reader Endpoint。
- Parameter Store路径和AWS Region。
- IAM Role至少允许目标Parameter的`ssm:GetParameter`。
- 根据业务最大事务时间调整`drain-timeout`。
- 为维护模式异常增加统一HTTP错误响应，建议返回`503 Service Unavailable`。

## 重要限制

- 需要Spring Framework 6.1+，因为使用了`TransactionExecutionListener`。
- 只统计Spring事务管理器管理的事务；绕过Spring直接取得JDBC连接的代码无法统计。
- `@Transactional(readOnly = false)`只表示“可能写入”，并不保证实际执行了UPDATE；为安全起见仍按写事务统计。
- 没有事务的JPA/JDBC写入不在排空统计范围内，应通过代码规范禁止，或额外在数据库账号层实施保护。
- Aurora Reader存在复制延迟。维护SQL提交后，应确认Reader已同步，再把Parameter改回`READ_WRITE`。
- `AbstractRoutingDataSource`只影响取得新连接的时刻；已经取得连接的事务不会中途换库，这正是排空旧事务的原因。

## 状态确认

启用的Actuator端点：

```text
GET /actuator/dbmode
```

示例：

```json
{
  "desiredMode": "READ_ONLY",
  "actualRoute": "READER",
  "state": "READY",
  "activeWriteTransactions": 0,
  "lastError": ""
}
```

## 测试

```bash
mvn test
```

重点测试了：等待存量写事务、排空期间拒绝新写事务、Writer健康检查失败时保持Reader。
