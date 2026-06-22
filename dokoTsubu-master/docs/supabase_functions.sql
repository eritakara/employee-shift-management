-- Supabase PostgreSQL 移行用 互換関数定義スクリプト
-- アプリケーション側の SQL を変更せずに PostgreSQL で動作させるために、
-- 以下の SQL を Supabase の SQL Editor などで実行してください。

-- 1. DATEADD 互換関数 (TIMESTAMP / DATE 用)
CREATE OR REPLACE FUNCTION dateadd(unit text, value integer, base timestamp)
RETURNS timestamp AS $$
BEGIN
  CASE lower(unit)
    WHEN 'day' THEN RETURN base + value * INTERVAL '1 day';
    WHEN 'month' THEN RETURN base + value * INTERVAL '1 month';
    WHEN 'year' THEN RETURN base + value * INTERVAL '1 year';
    ELSE RAISE EXCEPTION 'Unknown unit: %', unit;
  END CASE;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE OR REPLACE FUNCTION dateadd(unit text, value integer, base date)
RETURNS timestamp AS $$
BEGIN
  RETURN dateadd(unit, value, CAST(base AS timestamp));
END;
$$ LANGUAGE plpgsql IMMUTABLE;


-- 2. DATEDIFF 互換関数 (TIMESTAMP 用)
CREATE OR REPLACE FUNCTION datediff(unit text, start_t timestamp, end_t timestamp)
RETURNS integer AS $$
BEGIN
  CASE lower(unit)
    WHEN 'minute' THEN
      RETURN EXTRACT(EPOCH FROM (end_t - start_t))::integer / 60;
    WHEN 'day' THEN
      RETURN EXTRACT(DAY FROM (end_t - start_t))::integer;
    ELSE RAISE EXCEPTION 'Unknown unit: %', unit;
  END CASE;
END;
$$ LANGUAGE plpgsql IMMUTABLE;


-- 3. DATEDIFF 互換関数 (TIME 用)
-- 勤務区分の休憩・勤務時間（TIME型）の計算用
CREATE OR REPLACE FUNCTION datediff(unit text, start_t time, end_t time)
RETURNS integer AS $$
BEGIN
  CASE lower(unit)
    WHEN 'minute' THEN
      RETURN (EXTRACT(EPOCH FROM (end_t - start_t))::integer / 60);
    ELSE RAISE EXCEPTION 'Unknown unit: %', unit;
  END CASE;
END;
$$ LANGUAGE plpgsql IMMUTABLE;
