package aidhkm.dhkm16a1hn.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class NLPConfig {
    // Cấu hình các thành phần NLP nếu cần
    // VD: Stanford NLP, OpenNLP, hoặc OpenAI API keys
    
    // Cách khai báo bean Stanford NLP (nếu sử dụng)
    /*
    @Bean
    public StanfordCoreNLP pipeline() {
        Properties props = new Properties();
        props.setProperty("annotators", "tokenize, ssplit, pos, lemma, ner");
        return new StanfordCoreNLP(props);
    }
    */
} 