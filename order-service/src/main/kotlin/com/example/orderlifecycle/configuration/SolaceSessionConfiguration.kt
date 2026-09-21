package com.example.orderlifecycle.configuration

import com.solace.spring.boot.autoconfigure.SolaceJavaAutoConfiguration
import com.solacesystems.jcsmp.JCSMPSession
import com.solacesystems.jcsmp.SpringJCSMPFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

// solace-spring-boot-starter autoconfigures a SpringJCSMPFactory from the
// solace.java.* properties in application.properties. We only need to open one
// real JCSMPSession from it and hand that session to publishers/consumers.
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
