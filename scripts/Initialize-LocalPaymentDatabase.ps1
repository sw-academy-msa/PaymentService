<#
기존 일반 PostgreSQL에 Payment DB만 생성합니다. 운영 RDS 배포나 Command DB 이전 스크립트가 아닙니다.
비밀번호는 표준 입력으로 전달하며 콘솔·명령 인자에 출력하지 않습니다.
#>
param([string]$Container = 'stockdrop-account-postgres')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envPath = Join-Path $root '.env'
$utf8 = New-Object Text.UTF8Encoding($false)
if (Test-Path -LiteralPath $envPath) {
    $original = [IO.File]::ReadAllText($envPath)
    $match = [regex]::Matches($original,'(?m)^DB_PASSWORD=([^\r\n]+)')
    if ($match.Count -ne 1) { throw '기존 비밀번호를 안전하게 식별하지 못해 변경하지 않습니다.' }
    $usernames = [regex]::Matches($original,'(?m)^DB_USERNAME=([^\r\n]*)\r?$')
    if ($usernames.Count -ne 1 -or $usernames[0].Groups[1].Value -ne 'payment') { throw '다른 DB 계정 설정이 있어 변경하지 않습니다.' }
    $password = $match[0].Groups[1].Value
    # 공개된 예제 비밀번호로 역할을 생성하거나 기존 역할의 비밀번호를 변경하지 않습니다.
    if ($password -eq 'replace-with-generated-local-secret') { throw '예제 비밀번호를 실제 Payment 비밀번호로 설정해야 합니다.' }
} else {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $password = -join ($bytes | ForEach-Object { $_.ToString('x2') })
    $example = [IO.File]::ReadAllText((Join-Path $root '.env.example'))
    $original = [regex]::Replace($example,'(?m)^DB_PASSWORD=[^\r\n]*',('DB_PASSWORD='+$password))
    # 부분 실패 후 재시도에도 처음 생성한 자격증명을 잃지 않도록 먼저 저장합니다.
    $stream = [IO.File]::Open($envPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
    try { $data=$utf8.GetBytes($original); $stream.Write($data,0,$data.Length) } finally { $stream.Dispose() }
}
$template = @'
-- 로컬 일반 PostgreSQL에 Payment 전용 DB/계정/스키마만 생성합니다. 기존 Account/Command 데이터는 변경하지 않습니다.
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'stockdrop' OR NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'account') THEN
        RAISE EXCEPTION '로컬 일반 DB가 아닙니다. 대상 컨테이너를 확인하세요.';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payment'
        AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR NOT rolcanlogin)) THEN
        RAISE EXCEPTION '기존 Payment 역할의 권한이 예상과 다릅니다.';
    END IF;
    -- NOINHERIT만으로 다른 역할로의 SET ROLE을 막을 수 없어 기존 멤버십도 거절합니다.
    IF EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member WHERE r.rolname = 'payment') THEN
        RAISE EXCEPTION '기존 Payment 역할의 권한이 예상과 다릅니다.';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_database WHERE datname = 'payment' AND pg_get_userbyid(datdba) <> current_user) THEN
        RAISE EXCEPTION '기존 Payment DB 소유권이 예상과 다릅니다.';
    END IF;
END $$;
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payment') AS create_role \gset
\if :create_role
CREATE ROLE payment LOGIN PASSWORD :'payment_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
\else
-- 로컬 재실행 시 .env와 역할 자격증명을 동기화합니다. 운영 DB에는 실행하지 않습니다.
ALTER ROLE payment PASSWORD :'payment_password';
\endif
COMMIT;
SELECT NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'payment') AS create_database \gset
\if :create_database
CREATE DATABASE payment TEMPLATE template0 ENCODING 'UTF8';
\endif
REVOKE ALL ON DATABASE payment FROM PUBLIC;
GRANT CONNECT ON DATABASE payment TO payment;
ALTER ROLE payment IN DATABASE payment SET search_path TO payment;
\connect payment
BEGIN;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS payment AUTHORIZATION payment;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='payment' AND pg_get_userbyid(nspowner)='payment') THEN
        RAISE EXCEPTION 'Payment 스키마 소유권이 예상과 다릅니다.';
    END IF;
END $$;
REVOKE ALL ON SCHEMA payment FROM PUBLIC;
COMMIT;

'@
$literal = "'" + $password.Replace("'","''") + "'"
$nl = [Environment]::NewLine
$sql = "SET log_statement='none';"+$nl+"SET log_min_error_statement='panic';"+$nl+"SET standard_conforming_strings=on;"+$nl+$template.Replace(":'payment_password'",$literal)
$previous = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $captured = $sql | & docker exec -i $Container sh -c 'exec psql -X -U $POSTGRES_USER -d $POSTGRES_DB -v ON_ERROR_STOP=1' 2>&1
    $code = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previous
    $password = $null
    $literal = $null
    $sql = $null
    $original = $null
}
if ($code -ne 0) { throw 'Payment DB 준비 실패: 기존 DB와 환경 파일은 보존했습니다. 비밀값 없이 대상 상태를 확인해야 합니다.' }
Write-Output 'Payment DB/role/schema ready in the general PostgreSQL. Secrets withheld.'
