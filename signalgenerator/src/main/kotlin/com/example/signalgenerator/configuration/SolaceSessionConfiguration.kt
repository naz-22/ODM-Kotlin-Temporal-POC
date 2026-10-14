package com.example.signalgenerator.configuration

import com.solace.spring.boot.autoconfigure.SolaceJavaAutoConfiguration
import com.solacesystems.jcsmp.JCSMPSession
import com.solacesystems.jcsmp.SpringJCSMPFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration
@Import(SolaceJavaAutoConfiguration::class)
class SolaceSessionConfiguration {

    @Bean(destroyMethod = "closeSession")
    fun jcsmpSession(springJCSMPFactory: SpringJCSMPFactory): JCSMPSession {
        val session = springJCSMPFactory.createSession()
        session.connect()
        return session
    }
}
