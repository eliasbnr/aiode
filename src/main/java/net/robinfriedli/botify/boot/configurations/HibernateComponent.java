package net.robinfriedli.botify.boot.configurations;

import java.util.function.Consumer;
import java.util.function.Function;

import javax.persistence.EntityManagerFactory;

import net.robinfriedli.botify.command.CommandContext;
import net.robinfriedli.botify.function.HibernateInvoker;
import net.robinfriedli.botify.persist.StaticSessionProvider;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@EntityScan("net.robinfriedli.botify.entities")
public class HibernateComponent {

    private final EntityManagerFactory entityManagerFactory;

    public HibernateComponent(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
        StaticSessionProvider.sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
    }

    public Session getCurrentSession() {
        SessionFactory sessionFactory = getSessionFactory();
        return CommandContext.Current.optional().map(CommandContext::getSession).orElse(sessionFactory.getCurrentSession());
    }

    public SessionFactory getSessionFactory() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        if (sessionFactory == null) {
            throw new IllegalStateException("SessionFactory has not been set up yet");
        }

        return sessionFactory;
    }

    public void invokeWithSession(Consumer<Session> consumer) {
        HibernateInvoker.create(getCurrentSession()).invoke(consumer);
    }

    public <E> E invokeWithSession(Function<Session, E> function) {
        return HibernateInvoker.create(getCurrentSession()).invoke(function);
    }

}
