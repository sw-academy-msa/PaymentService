# 생성한 Payment 계정으로 TCP 인증·스키마 소유권·DDL/DML 권한을 롤백 가능한 방식으로 확인합니다.
param([string]$Container = 'stockdrop-account-postgres')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envText = [IO.File]::ReadAllText((Join-Path $root '.env'))
$match = [regex]::Match($envText,'(?m)^DB_PASSWORD=([^\r\n]+)')
if (-not $match.Success) { throw 'Payment 비밀번호 설정이 필요합니다.' }
$sql = @'
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'payment' OR current_user <> 'payment' OR current_schema() <> 'payment' THEN
        RAISE EXCEPTION 'Payment 접속 경계 불일치';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN
        RAISE EXCEPTION 'Payment 역할 과다 권한';
    END IF;
    IF has_schema_privilege(current_user,'public','CREATE') OR has_database_privilege(current_user,current_database(),'CREATE') THEN
        RAISE EXCEPTION '다른 스키마 생성 권한이 있음';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname IN ('account','product','orders')) THEN
        RAISE EXCEPTION '다른 서비스 원본 스키마가 Payment DB에 있음';
    END IF;
END $$;
CREATE TABLE payment.payment_permission_probe(id integer PRIMARY KEY);
INSERT INTO payment.payment_permission_probe VALUES (1);
UPDATE payment.payment_permission_probe SET id=2 WHERE id=1;
DELETE FROM payment.payment_permission_probe;
ROLLBACK;
SELECT 'PAYMENT_PERMISSION_CHECK_PASS';
'@
$inputText = $match.Groups[1].Value + [Environment]::NewLine + $sql
$previous = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $output = $inputText | & docker exec -i $Container sh -c 'IFS= read -r PGPASSWORD; export PGPASSWORD; exec psql -X -h 127.0.0.1 -U payment -d payment -v ON_ERROR_STOP=1' 2>&1
    $code = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previous
    $inputText = $null
    $envText = $null
}
if ($code -ne 0 -or -not ($output -match 'PAYMENT_PERMISSION_CHECK_PASS')) { throw 'Payment DB 권한 검증 실패. 원문 비밀값은 출력하지 않습니다.' }
Write-Output 'Payment TCP authentication and own-schema permissions: PASS'
