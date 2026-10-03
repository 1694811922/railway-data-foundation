package cn.railway.foundation.metadata;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.ConnectException;

/** Classify vendor codes and exception types only; raw driver messages can disclose secrets. */
public final class SourceConnectionFailure {
    private SourceConnectionFailure() {}
    public static String reason(SQLException error) {
        for (SQLException current=error;current!=null;current=current.getNextException()) {
            if (current.getErrorCode()==1045 || "28000".equals(current.getSQLState())) return "用户名或密码错误";
            if (current.getErrorCode()==1049) return "数据库名称不存在";
            if (current.getErrorCode()==1044 || current.getErrorCode()==1142 || current.getErrorCode()==1227) return "当前账号权限不足";
            if (current instanceof SQLTimeoutException) return "网络连接或查询超时";
        }
        for (Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if (cause instanceof SocketTimeoutException) return "网络连接超时";
            if (cause instanceof UnknownHostException) return "数据库地址无法解析，请检查 IP 或域名";
            if (cause instanceof ConnectException) return "数据库地址或端口无法连接";
        }
        if (error.getSQLState()!=null && error.getSQLState().startsWith("08")) return "数据库地址不可达或连接已中断";
        return "数据库连接失败，请检查连接配置、加密连接设置和账号权限";
    }
}
