package cn.railway.foundation.security;

import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.CredentialsContainer;
import java.util.*;

public final class WorkspaceUser implements UserDetails,CredentialsContainer {
    public final String id,username,displayName,role,subjectId,subjectName,department;
    public final int version;
    private String hash;
    public WorkspaceUser(String id,String username,String displayName,String role,String subjectId,String subjectName,String department,int version,String hash){
        this.id=id;this.username=username;this.displayName=displayName;this.role=role;this.subjectId=subjectId;this.subjectName=subjectName;this.department=department;this.version=version;this.hash=hash;
    }
    public Collection<? extends GrantedAuthority> getAuthorities(){return List.of(new SimpleGrantedAuthority("ROLE_"+role));}
    public String getPassword(){return hash;}
    public String getUsername(){return username;}
    public void eraseCredentials(){hash=null;}
    @Override public String toString(){return "WorkspaceUser["+id+"]";}
}
