# OpenAgent 登录信息

## 访问地址
- **URL**: http://127.0.0.1:18954

## 账号信息

### 管理员账号
- **用户名**: `myuser`
- **密码**: `MyPass1234`
- **角色**: super_admin
- **邮箱**: myuser@test.com

## 登录方式

### 1. Web 界面
直接在浏览器访问 http://127.0.0.1:18954 即可使用 Web 界面登录。

### 2. API 登录
```bash
curl -X POST http://127.0.0.1:18954/api/login \
  -H "Content-Type: application/json" \
  -d '{"login":"myuser","password":"MyPass1234"}'
```

## 其他说明
- 注册功能已开启，可以创建新账号
- 数据库中还存在 local-user（本地固定用户）和 admin 用户
