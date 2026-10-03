package cn.railway.foundation.credential;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record SourceCredential(String username, @JsonIgnore String password) {
    @Override public String toString() { return "SourceCredential[redacted]"; }
}
