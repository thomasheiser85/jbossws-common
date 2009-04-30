/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.wsf.common.javax;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.xml.ws.WebServiceContext;

import org.jboss.logging.Logger;
import org.jboss.wsf.common.javax.finders.EJBFieldFinder;
import org.jboss.wsf.common.javax.finders.EJBMethodFinder;
import org.jboss.wsf.common.javax.finders.InjectionFieldFinder;
import org.jboss.wsf.common.javax.finders.InjectionMethodFinder;
import org.jboss.wsf.common.javax.finders.PostConstructMethodFinder;
import org.jboss.wsf.common.javax.finders.PreDestroyMethodFinder;
import org.jboss.wsf.common.javax.finders.ResourceFieldFinder;
import org.jboss.wsf.common.javax.finders.ResourceMethodFinder;
import org.jboss.wsf.common.reflection.ClassProcessor;
import org.jboss.wsf.spi.metadata.injection.InjectionMetaData;
import org.jboss.wsf.spi.metadata.injection.InjectionsMetaData;

/**
 * An injection helper class for <b>javax.*</b> annotations.
 *
 * @author <a href="mailto:richard.opalka@jboss.org">Richard Opalka</a>
 */
public final class JavaxAnnotationHelper
{

   private static final Logger LOG = Logger.getLogger(JavaxAnnotationHelper.class);
   private static final String POJO_JNDI_PREFIX = "java:comp/env/";

   private static final ClassProcessor<Method> POST_CONSTRUCT_METHOD_FINDER = new PostConstructMethodFinder();
   private static final ClassProcessor<Method> PRE_DESTROY_METHOD_FINDER = new PreDestroyMethodFinder();
   private static final ClassProcessor<Method> RESOURCE_METHOD_FINDER = new ResourceMethodFinder(WebServiceContext.class, false);
   private static final ClassProcessor<Field> RESOURCE_FIELD_FINDER = new ResourceFieldFinder(WebServiceContext.class, false);
   private static final ClassProcessor<Method> EJB_METHOD_FINDER = new EJBMethodFinder();
   private static final ClassProcessor<Field> EJB_FIELD_FINDER = new EJBFieldFinder();
   private static final ClassProcessor<Method> WEB_SERVICE_CONTEXT_METHOD_FINDER = new ResourceMethodFinder(WebServiceContext.class, true);
   private static final ClassProcessor<Field> WEB_SERVICE_CONTEXT_FIELD_FINDER = new ResourceFieldFinder(WebServiceContext.class, true);

   /**
    * Forbidden constructor.
    */
   private JavaxAnnotationHelper()
   {
      super();
   }

   /**
    * The resource annotations mark resources that are needed by the application. These annotations may be applied
    * to an application component class, or to fields or methods of the component class. When the annotation is
    * applied to a field or method, the container will inject an instance of the requested resource into the
    * application component when the component is initialized. If the annotation is applied to the component class,
    * the annotation declares a resource that the application will look up at runtime.
    * 
    * This method handles the following injection types:
    * <ul>
    *   <li>Descriptor specified injections</li>
    *   <li>@Resource annotated methods and fields</li>
    *   <li>@EJB annotated methods and fields</li>
    * </ul>  
    *
    * @param instance to inject resources on
    * @param injections injections metadata
    * @throws Exception if some error occurs
    */
   public static void injectResources(final Object instance, final InjectionsMetaData injections) throws Exception
   {
      if (instance == null)
         throw new IllegalArgumentException("Object instance cannot be null");
      
      if (injections == null)
         return;

      // get JNDI context
      Context ctx = injections.getContext();
      if (ctx == null)
      {
         ctx = (Context)new InitialContext().lookup(POJO_JNDI_PREFIX);
      }

      // inject descriptor driven annotations
      final Collection<InjectionMetaData> injectionMDs = injections.getInjectionsMetaData(instance.getClass());
      for (InjectionMetaData injectionMD : injectionMDs)
      {
         injectDescriptorDrivenInjections(instance, ctx, injectionMD);
      }

      // inject @Resource annotated methods and fields
      injectResourceAnnotatedMethods(instance, ctx);
      injectResourceAnnotatedFields(instance, ctx);

      // inject @EJB annotated methods and fields
      injectEJBAnnotatedMethods(instance, ctx);
      injectEJBAnnotatedFields(instance, ctx);
   }

   /**
    * Performs descriptor driven injections.
    * @param instance to operate on
    * @param ctx JNDI context
    * @param envPrefix env prefix to be used
    * @param injectionMD injections metadata
    */
   private static void injectDescriptorDrivenInjections(final Object instance, final Context ctx, final InjectionMetaData injectionMD)
   {
      Method method = getMethod(injectionMD, instance.getClass());
      if (method != null)
      {
         try
         {
            inject(instance, method, injectionMD.getEnvEntryName(), ctx);
         }
         catch (Exception e)
         {
            LOG.error("Cannot inject method (descriptor driven injection): " + injectionMD, e);
         }
      }
      else
      {
         Field field = getField(injectionMD, instance.getClass());
         if (field != null)
         {
            try
            {
               inject(instance, field, injectionMD.getEnvEntryName(), ctx);
            }
            catch (Exception e)
            {
               LOG.error("Cannot inject field (descriptor driven injection): " + injectionMD, e);
            }
         }
         else
         {
            LOG.error("Cannot find injection target for: " + injectionMD);
         }
      }
   }

   /**
    * Injects @Resource annotated fields
    * @param instance to operate on
    * @param ctx JNDI context
    * @param envPrefix environment prefix to be used
    */
   private static void injectResourceAnnotatedFields(final Object instance, final Context ctx)
   {
      Collection<Field> resourceAnnotatedFields = RESOURCE_FIELD_FINDER.process(instance.getClass());
      for (Field field : resourceAnnotatedFields)
      {
         try
         {
            inject(instance, field, field.getAnnotation(Resource.class).name(), ctx);
         }
         catch (Exception e)
         {
            LOG.error("Cannot inject field annotated with @Resource annotation: " + field, e);
         }
      }
   }

   /**
    * Injects @Resource annotated methods
    * @param instance to operate on
    * @param ctx JNDI context
    * @param envPrefix environment prefix to be used
    */
   private static void injectResourceAnnotatedMethods(final Object instance, final Context ctx)
   {
      Collection<Method> resourceAnnotatedMethods = RESOURCE_METHOD_FINDER.process(instance.getClass());
      for(Method method : resourceAnnotatedMethods)
      {
         try
         {
            inject(instance, method, method.getAnnotation(Resource.class).name(), ctx);
         }
         catch (Exception e)
         {
            LOG.error("Cannot inject method annotated with @Resource annotation: " + method, e);
         }
      }
   }

   /**
    * Injects @EJB annotated fields
    * @param instance to operate on
    * @param ctx JNDI context
    * @param envPrefix environment prefix to be used
    */
   private static void injectEJBAnnotatedFields(final Object instance, final Context ctx)
   {
      final Collection<Field> ejbAnnotatedFields = EJB_FIELD_FINDER.process(instance.getClass());
      for (Field field : ejbAnnotatedFields)
      {
         try
         {
            inject(instance, field, field.getAnnotation(EJB.class).name(), ctx);
         }
         catch (Exception e)
         {
            LOG.error("Cannot inject field annotated with @EJB annotation: " + field, e);
         }
      }
   }

   /**
    * Injects @EJB annotated methods
    * @param instance to operate on
    * @param ctx JNDI context
    * @param envPrefix environment prefix to be used
    */
   private static void injectEJBAnnotatedMethods(final Object instance, final Context ctx)
   {
      final Collection<Method> ejbAnnotatedMethods = EJB_METHOD_FINDER.process(instance.getClass());
      for(Method method : ejbAnnotatedMethods)
      {
         try
         {
            inject(instance, method, method.getAnnotation(EJB.class).name(), ctx);
         }
         catch (Exception e)
         {
            LOG.error("Cannot inject method annotated with @EJB annotation: " + method, e);
         }
      }
   }

   public static void injectWebServiceContext(final Object instance, final WebServiceContext ctx)
   {
      final Class<?> instanceClass = instance.getClass();

      // inject @Resource annotated methods accepting WebServiceContext parameter
      Collection<Method> resourceAnnotatedMethods = WEB_SERVICE_CONTEXT_METHOD_FINDER.process(instanceClass);
      for(Method method : resourceAnnotatedMethods)
      {
         try
         {
            invokeMethod(instance, method, new Object[] {ctx});
         }
         catch (Exception e)
         {
            LOG.error("Cannot inject @Resource annotated method: " + method, e);
         }
      }

      // inject @Resource annotated fields of WebServiceContext type
      Collection<Field> resourceAnnotatedFields = WEB_SERVICE_CONTEXT_FIELD_FINDER.process(instanceClass);
      for (Field field : resourceAnnotatedFields)
      {
         try
         {
            setField(instance, field, ctx);
         }
         catch (Exception e)
         {
            LOG.error("Cannot inject @Resource annotated field: " + field, e);
         }
      }
   }

   /**
    * Calls @PostConstruct annotated method if exists.
    *
    * @param instance to invoke @PostConstruct annotated method on
    * @throws Exception if some error occurs
    * @see org.jboss.wsf.common.javax.finders.PostConstructMethodFinder
    * @see javax.annotation.PostConstruct
    */
   public static void callPostConstructMethod(final Object instance) throws Exception
   {
      if (instance == null)
         throw new IllegalArgumentException("Object instance cannot be null");

      Collection<Method> methods = POST_CONSTRUCT_METHOD_FINDER.process(instance.getClass());

      if (methods.size() > 0)
      {
         Method method = methods.iterator().next();
         LOG.debug("Calling @PostConstruct annotated method: " + method);
         try
         {
            invokeMethod(instance, method, null);
         }
         catch (Exception e)
         {
            LOG.error("Calling of @PostConstruct annotated method failed: " + method, e);
         }
      }
   }

   /**
    * Calls @PreDestroy annotated method if exists.
    *
    * @param instance to invoke @PreDestroy annotated method on
    * @throws Exception if some error occurs
    * @see org.jboss.wsf.common.javax.finders.PreDestroyMethodFinder
    * @see javax.annotation.PreDestroy
    */
   public static void callPreDestroyMethod(final Object instance) throws Exception
   {
      if (instance == null)
         throw new IllegalArgumentException("Object instance cannot be null");

      Collection<Method> methods = PRE_DESTROY_METHOD_FINDER.process(instance.getClass());

      if (methods.size() > 0)
      {
         Method method = methods.iterator().next();
         LOG.debug("Calling @PreDestroy annotated method: " + method);
         try
         {
            invokeMethod(instance, method, null);
         }
         catch (Exception e)
         {
            LOG.error("Calling of @PreDestroy annotated method failed: " + method, e);
         }
      }
   }

   /**
    * Injects @Resource annotated method.
    *
    * @param instance to invoke method on
    * @param method to invoke
    * @param resourceName resource name
    * @param cxt JNDI context
    * @param envPrefix JNDI environment prefix
    * @throws Exception if any error occurs
    * @see org.jboss.wsf.common.javax.finders.ResourceMethodFinder
    */
   private static void inject(final Object instance, final Method method, final String resourceName, final Context ctx)
   throws Exception
   {
      final String beanName = convertToBeanName(method.getName()); 
      final Object value = ctx.lookup(getName(resourceName, beanName));

      LOG.debug("Injecting method: " + method);
      invokeMethod(instance, method, new Object[] {value});
   }

   /**
    * Injects @Resource annotated field.
    *
    * @param field to set
    * @param instance to modify field on
    * @param resourceName resource name
    * @param cxt JNDI context
    * @param envPrefix JNDI environment prefix
    * @throws Exception if any error occurs
    * @see org.jboss.wsf.common.javax.finders.ResourceFieldFinder
    */
   private static void inject(final Object instance, final Field field, final String resourceName, final Context ctx)
   throws Exception
   {
      final String beanName = field.getName();
      final Object value = ctx.lookup(getName(resourceName, beanName));

      LOG.debug("Injecting field: " + field);
      setField(instance, field, value);
   }

   /**
    * Translates "setBeanName" to "beanName" string.
    *
    * @param methodName to translate
    * @return bean name
    */
   private static String convertToBeanName(final String methodName)
   {
      return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
   }

   /**
    * Returns full JNDI name.
    *
    * @param resourceName to be used if specified
    * @param beanName fallback bean name to be used
    * @return JNDI full name
    */
   private static String getName(final String resourceName, final String beanName)
   {
      return resourceName.length() > 0 ? resourceName : beanName;
   }

   /**
    * Invokes method on object with specified arguments.
    *
    * @param instance to invoke method on
    * @param method method to invoke
    * @param args arguments to pass
    * @throws Exception if any error occurs
    */
   private static void invokeMethod(final Object instance, final Method method, final Object[] args)
   throws Exception
   {
      boolean accessability = method.isAccessible();

      try
      {
         method.setAccessible(true);
         method.invoke(instance, args);
      }
      finally
      {
         method.setAccessible(accessability);
      }
   }

   /**
    * Sets field on object with specified value.
    * 
    * @param instance to set field on
    * @param field to set
    * @param value to be set
    * @throws Exception if any error occurs
    */
   private static void setField(final Object instance, final Field field, final Object value)
   throws Exception
   {
      boolean accessability = field.isAccessible();

      try
      {
         field.setAccessible(true);
         field.set(instance, value);
      }
      finally
      {
         field.setAccessible(accessability);
      }
   }

   /**
    * Returns method that matches the descriptor injection metadata or null if not found.
    *
    * @param injectionMD descriptor injection metadata
    * @param clazz to process
    * @return method that matches the criteria or null if not found
    * @see org.jboss.wsf.common.javax.finders.InjectionMethodFinder
    */
   private static Method getMethod(final InjectionMetaData injectionMD, final Class<?> clazz)
   {
      final Collection<Method> result = new InjectionMethodFinder(injectionMD).process(clazz);

      return result.isEmpty() ? null : result.iterator().next();
   }

   /**
    * Returns field that matches the descriptor injection metadata or null if not found.
    *
    * @param injectionMD descriptor injection metadata
    * @param clazz to process
    * @return field that matches the criteria or null if not found
    * @see org.jboss.wsf.common.javax.finders.InjectionFieldFinder
    */
   private static Field getField(final InjectionMetaData injectionMD, final Class<?> clazz)
   {
      final Collection<Field> result = new InjectionFieldFinder(injectionMD).process(clazz);

      return result.isEmpty() ? null : result.iterator().next();
   }

}
