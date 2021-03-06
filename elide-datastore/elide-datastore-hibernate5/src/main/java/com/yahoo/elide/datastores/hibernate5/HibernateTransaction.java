/*
 * Copyright 2016, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.datastores.hibernate5;

import com.yahoo.elide.core.DataStoreTransaction;
import com.yahoo.elide.core.EntityDictionary;
import com.yahoo.elide.core.Path;
import com.yahoo.elide.core.RequestScope;
import com.yahoo.elide.core.exceptions.TransactionException;
import com.yahoo.elide.core.filter.FilterPredicate;
import com.yahoo.elide.core.filter.Operator;
import com.yahoo.elide.core.filter.expression.AndFilterExpression;
import com.yahoo.elide.core.filter.expression.FilterExpression;
import com.yahoo.elide.core.hibernate.hql.AbstractHQLQueryBuilder;
import com.yahoo.elide.core.hibernate.hql.RelationshipImpl;
import com.yahoo.elide.core.hibernate.hql.RootCollectionFetchQueryBuilder;
import com.yahoo.elide.core.hibernate.hql.RootCollectionPageTotalsQueryBuilder;
import com.yahoo.elide.core.hibernate.hql.SubCollectionFetchQueryBuilder;
import com.yahoo.elide.core.hibernate.hql.SubCollectionPageTotalsQueryBuilder;
import com.yahoo.elide.core.pagination.Pagination;
import com.yahoo.elide.core.sort.Sorting;
import com.yahoo.elide.datastores.hibernate5.porting.QueryWrapper;
import com.yahoo.elide.datastores.hibernate5.porting.SessionWrapper;
import com.yahoo.elide.security.User;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.FlushMode;
import org.hibernate.ObjectNotFoundException;
import org.hibernate.ScrollMode;
import org.hibernate.Session;
import org.hibernate.collection.internal.AbstractPersistentCollection;
import org.hibernate.resource.transaction.spi.TransactionStatus;

import javax.persistence.PersistenceException;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;

/**
 * Hibernate Transaction implementation.
 */
@Slf4j
public class HibernateTransaction implements DataStoreTransaction {

    private final Session session;
    private final SessionWrapper sessionWrapper;
    private final LinkedHashSet<Runnable> deferredTasks = new LinkedHashSet<>();
    private final boolean isScrollEnabled;
    private final ScrollMode scrollMode;

    /**
     * Constructor.
     *
     * @param session Hibernate session
     * @param isScrollEnabled Whether or not scrolling is enabled
     * @param scrollMode Scroll mode to use if scrolling enabled
     */
    protected HibernateTransaction(Session session, boolean isScrollEnabled, ScrollMode scrollMode) {
        this.session = session;
        this.sessionWrapper = new SessionWrapper(session);
        this.isScrollEnabled = isScrollEnabled;
        this.scrollMode = scrollMode;
    }

    @Override
    public void delete(Object object, RequestScope scope) {
        deferredTasks.add(() -> session.delete(object));
    }

    @Override
    public void save(Object object, RequestScope scope) {
        deferredTasks.add(() -> session.saveOrUpdate(object));
    }

    @Override
    public void flush(RequestScope requestScope) {
        try {
            deferredTasks.forEach(Runnable::run);
            deferredTasks.clear();
            FlushMode flushMode = session.getHibernateFlushMode();
            if (flushMode != FlushMode.COMMIT && flushMode != FlushMode.MANUAL) {
                session.flush();
            }
        } catch (PersistenceException e) {
            log.error("Caught hibernate exception during flush", e);
            throw new TransactionException(e);
        }
    }

    @Override
    public void commit(RequestScope scope) {
        try {
            this.flush(scope);
            this.session.getTransaction().commit();
        } catch (PersistenceException e) {
            throw new TransactionException(e);
        }
    }

    @Override
    public void createObject(Object entity, RequestScope scope) {
        deferredTasks.add(() -> session.persist(entity));
    }

    /**
     * load a single record with id and filter.
     *
     * @param entityClass class of query object
     * @param id id of the query object
     * @param filterExpression FilterExpression contains the predicates
     * @param scope Request scope associated with specific request
     */
    @Override
    public Object loadObject(Class<?> entityClass,
                             Serializable id,
                             Optional<FilterExpression> filterExpression,
                             RequestScope scope) {

        try {
            EntityDictionary dictionary = scope.getDictionary();
            Class<?> idType = dictionary.getIdType(entityClass);
            String idField = dictionary.getIdFieldName(entityClass);

            //Construct a predicate that selects an individual element of the relationship's parent (Author.id = 3).
            FilterPredicate idExpression;
            Path.PathElement idPath = new Path.PathElement(entityClass, idType, idField);
            if (id != null) {
                idExpression = new FilterPredicate(idPath, Operator.IN, Collections.singletonList(id));
            } else {
                idExpression = new FilterPredicate(idPath, Operator.FALSE, Collections.emptyList());
            }

            FilterExpression joinedExpression = filterExpression
                    .map(fe -> (FilterExpression) new AndFilterExpression(fe, idExpression))
                    .orElse(idExpression);

            QueryWrapper query =
                    (QueryWrapper) new RootCollectionFetchQueryBuilder(entityClass, dictionary, sessionWrapper)
                    .withPossibleFilterExpression(Optional.of(joinedExpression))
                    .build();

            return query.getQuery().uniqueResult();
        } catch (ObjectNotFoundException e) {
            return null;
        }
    }

    @Override
    public Iterable<Object> loadObjects(
            Class<?> entityClass,
            Optional<FilterExpression> filterExpression,
            Optional<Sorting> sorting,
            Optional<Pagination> pagination,
            RequestScope scope) {

        pagination.ifPresent(p -> {
            if (p.isGenerateTotals()) {
                p.setPageTotals(getTotalRecords(entityClass, filterExpression, scope.getDictionary()));
            }
        });

        final QueryWrapper query =
                (QueryWrapper) new RootCollectionFetchQueryBuilder(entityClass, scope.getDictionary(), sessionWrapper)
                        .withPossibleFilterExpression(filterExpression)
                        .withPossibleSorting(sorting)
                        .withPossiblePagination(pagination)
                        .build();


        if (isScrollEnabled) {
            return new ScrollableIterator<>(query.getQuery().scroll());
        }
        return query.getQuery().list();
    }

    @Override
    public Object getRelation(
            DataStoreTransaction relationTx,
            Object entity,
            String relationName,
            Optional<FilterExpression> filterExpression,
            Optional<Sorting> sorting,
            Optional<Pagination> pagination,
            RequestScope scope) {

        EntityDictionary dictionary = scope.getDictionary();
        Object val = com.yahoo.elide.core.PersistentResource.getValue(entity, relationName, scope);
        if (val instanceof Collection) {
            Collection filteredVal = (Collection) val;
            if (filteredVal instanceof AbstractPersistentCollection) {
                Class<?> relationClass = dictionary.getParameterizedType(entity, relationName);

                RelationshipImpl relationship = new RelationshipImpl(
                        dictionary.lookupEntityClass(entity.getClass()),
                        relationClass,
                        relationName,
                        entity,
                        filteredVal);

                pagination.ifPresent(p -> {
                    if (p.isGenerateTotals()) {
                        p.setPageTotals(getTotalRecords(relationship, filterExpression, dictionary));
                    }
                });

                final QueryWrapper query = (QueryWrapper)
                        new SubCollectionFetchQueryBuilder(relationship, dictionary, sessionWrapper)
                                .withPossibleFilterExpression(filterExpression)
                                .withPossibleSorting(sorting)
                                .withPossiblePagination(pagination)
                                .build();

                if (query != null) {
                    return query.getQuery().list();
                }
            }
        }
        return val;
    }

    /**
     * Returns the total record count for a root entity and an optional filter expression.
     * @param entityClass The entity type to count
     * @param filterExpression optional security and request filters
     * @param dictionary the entity dictionary
     * @param <T> The type of entity
     * @return The total row count.
     */
    private <T> Long getTotalRecords(Class<T> entityClass,
                                     Optional<FilterExpression> filterExpression,
                                     EntityDictionary dictionary) {


        QueryWrapper query = (QueryWrapper)
                new RootCollectionPageTotalsQueryBuilder(entityClass, dictionary, sessionWrapper)
                .withPossibleFilterExpression(filterExpression)
                .build();

        return (Long) query.getQuery().uniqueResult();
    }

    /**
     * Returns the total record count for a entity relationship
     * @param relationship The relationship
     * @param filterExpression optional security and request filters
     * @param dictionary the entity dictionary
     * @param <T> The type of entity
     * @return The total row count.
     */
    private <T> Long getTotalRecords(AbstractHQLQueryBuilder.Relationship relationship,
                                     Optional<FilterExpression> filterExpression,
                                     EntityDictionary dictionary) {

        QueryWrapper query = (QueryWrapper)
                new SubCollectionPageTotalsQueryBuilder(relationship, dictionary, sessionWrapper)
                .withPossibleFilterExpression(filterExpression)
                .build();

        return (Long) query.getQuery().uniqueResult();
    }

    @Override
    public void close() throws IOException {
        if (session.isOpen() && session.getTransaction().getStatus() == TransactionStatus.ACTIVE) {
            session.getTransaction().rollback();
            throw new IOException("Transaction not closed");
        }
    }

    @Override
    public User accessUser(Object opaqueUser) {
        return new User(opaqueUser);
    }
}
