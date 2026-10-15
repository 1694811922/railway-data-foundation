package cn.railway.foundation.credential;

import cn.railway.foundation.api.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CredentialEncryptionTest {
    static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    EncryptedCredentialService service(MockEnvironment environment) {
        return new EncryptedCredentialService(mock(JdbcTemplate.class),environment);
    }
    @Test void encryptsWithRandomNonceAndBindsSecretToCredentialReference() {
        var service=service(new MockEnvironment().withProperty("FOUNDATION_CREDENTIAL_KEY",KEY));
        String first=service.encrypt("CRED_A","测试-secret"),second=service.encrypt("CRED_A","测试-secret");
        assertThat(first).isNotEqualTo(second).doesNotContain("测试-secret");
        assertThat(service.decrypt("CRED_A",first)).isEqualTo("测试-secret");
        assertThatThrownBy(()->service.decrypt("CRED_B",first)).isInstanceOf(BusinessException.class).hasMessageContaining("凭据不可用");
        assertThatThrownBy(()->service.decrypt("CRED_A",first.substring(0,first.length()-4)+"AAAA"))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining("测试-secret");
    }
    @Test void rejectsMissingKeyAndWrongKeyWithoutLeakingDetails() {
        assertThatThrownBy(()->service(new MockEnvironment()).encrypt("CRED_A","secret"))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining("secret");
        var correct=service(new MockEnvironment().withProperty("FOUNDATION_CREDENTIAL_KEY",KEY));
        byte[] changed=new byte[32];changed[0]=1;
        var wrong=service(new MockEnvironment().withProperty("FOUNDATION_CREDENTIAL_KEY",Base64.getEncoder().encodeToString(changed)));
        assertThatThrownBy(()->wrong.decrypt("CRED_A",correct.encrypt("CRED_A","secret")))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining("secret");
        assertThat(new SourceCredential("reader","secret").toString()).doesNotContain("reader","secret");
    }
    @Test void subscriptionEnvelopeCannotBeCopiedToAnotherGrantIdentityOrSource() {
        var service=service(new MockEnvironment().withProperty("FOUNDATION_CREDENTIAL_KEY",KEY));
        String grant="KSG_"+"a".repeat(32),user="rdfk_"+"b".repeat(32);
        String value=service.sealSubscription(grant,user,"fixture-subscription-secret");
        assertThat(value).doesNotContain("fixture-subscription-secret");
        assertThat(service.openSubscription(grant,user,value)).isEqualTo("fixture-subscription-secret");
        assertThatThrownBy(()->service.openSubscription("KSG_"+"c".repeat(32),user,value)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.openSubscription(grant,"rdfk_"+"d".repeat(32),value)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.decrypt(grant,value)).isInstanceOf(BusinessException.class);
    }
}
