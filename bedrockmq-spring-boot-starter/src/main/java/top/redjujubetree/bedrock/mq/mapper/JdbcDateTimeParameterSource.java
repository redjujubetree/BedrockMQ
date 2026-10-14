package top.redjujubetree.bedrock.mq.mapper;

import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/** Binds Java time values through JDBC's TIMESTAMP type for drivers without LocalDateTime support. */
final class JdbcDateTimeParameterSource extends MapSqlParameterSource {

    JdbcDateTimeParameterSource() {
    }

    JdbcDateTimeParameterSource(String name, Object value) {
        addValue(name, value);
    }

    @Override
    public JdbcDateTimeParameterSource addValue(String name, Object value) {
        super.addValue(name, jdbcValue(value));
        return this;
    }

    @Override
    public JdbcDateTimeParameterSource addValue(String name, Object value, int sqlType) {
        super.addValue(name, jdbcValue(value), sqlType);
        return this;
    }

    static BeanPropertySqlParameterSource bean(Object value) {
        return new BeanPropertySqlParameterSource(value) {
            @Override
            public Object getValue(String paramName) throws IllegalArgumentException {
                return jdbcValue(super.getValue(paramName));
            }
        };
    }

    private static Object jdbcValue(Object value) {
        return value instanceof LocalDateTime ? Timestamp.valueOf((LocalDateTime) value) : value;
    }
}
