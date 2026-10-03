package cn.railway.foundation.credential;

public interface CredentialService {
    /** Null reference creates a credential; an existing reference updates its encrypted secret. */
    String saveCredential(String existingRef, String username, String password);
    SourceCredential getCredential(String credentialRef);
    void updateUsername(String credentialRef, String username);
    void deleteCredential(String credentialRef);
    /** Compatibility for previously registered environment references. */
    SourceCredential fromEnvironment(String prefix);
}
