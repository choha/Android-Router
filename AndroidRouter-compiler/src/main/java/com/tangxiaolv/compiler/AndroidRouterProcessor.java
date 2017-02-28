
package com.tangxiaolv.compiler;

import com.google.auto.common.SuperficialValidation;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import com.tangxiaolv.annotations.RouterModule;
import com.tangxiaolv.annotations.RouterPath;

import static com.tangxiaolv.compiler.PUtils.isEmpty;

public class AndroidRouterProcessor extends AbstractProcessor {

    private static final String PACKAGE_NAME = "com.tangxiaolv.router.module";
    private static final String PREFIX = "Mirror_";
    private static final ClassName PARAMS_WRAPPER = ClassName.get("com.tangxiaolv.router",
            "ParamsWrapper");
    private static final ClassName METHOD = ClassName.get("java.lang.reflect", "Method");
    private static final ClassName TEXTUTILS = ClassName.get("android.text", "TextUtils");
    private static final ClassName NOT_FOUND_PATH_EXCEPTION = ClassName
            .get("com.tangxiaolv.router.exceptions", "NotFoundPathException");

    private Elements elementUtils;
    private Filer filer;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        elementUtils = env.getElementUtils();
        filer = env.getFiler();
    }

    @Override
    public Set<String> getSupportedOptions() {
        return super.getSupportedOptions();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (Class<? extends Annotation> annotation : getSupportedAnnotations()) {
            types.add(annotation.getCanonicalName());
        }
        return types;
    }

    private Set<Class<? extends Annotation>> getSupportedAnnotations() {
        Set<Class<? extends Annotation>> annotations = new LinkedHashSet<>();
        annotations.add(RouterModule.class);
        return annotations;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment env) {
        List<JavaFile> files = findAndParseTargets(env);
        for (JavaFile javaFile : files) {
            try {
                javaFile.writeTo(filer);
            } catch (IOException e) {
                error("Unable to write same name %s: %s", javaFile.packageName, e.getMessage());
            }
        }
        return false;
    }

    private void error(String message, Object... args) {
        printMessage(Diagnostic.Kind.ERROR, message, args);
    }

    private void note(String message, Object... args) {
        printMessage(Diagnostic.Kind.NOTE, message, args);
    }

    private void printMessage(Diagnostic.Kind kind, String message,
                              Object[] args) {
        if (args.length > 0) {
            message = String.format(message, args);
        }
        processingEnv.getMessager().printMessage(kind, message);
    }

    private List<JavaFile> findAndParseTargets(RoundEnvironment env) {
        List<JavaFile> javaFiles = new ArrayList<>();

        // Process each @RouterModule element.
        for (Element e : env.getElementsAnnotatedWith(RouterModule.class)) {
            if (!SuperficialValidation.validateElement(e))
                continue;
            List<? extends Element> allEle = e.getEnclosedElements();
            parseRouterModule(e, allEle, javaFiles);
        }
        return javaFiles;
    }

    private void parseRouterModule(Element moduleEle, List<? extends Element> allEle,
                                   List<JavaFile> javaFiles) {
        RouterModule moduleAnno = moduleEle.getAnnotation(RouterModule.class);
        String schemes = moduleAnno.scheme();
        String host = moduleAnno.host().toLowerCase();
        if (isEmpty(schemes) || isEmpty(host))
            return;

        // method build
        MethodSpec.Builder invokeBuilder = MethodSpec.methodBuilder("invoke");
        invokeBuilder.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .returns(void.class)
                .addParameter(String.class, "path")
                .addParameter(PARAMS_WRAPPER, "params")
                .addException(NOT_FOUND_PATH_EXCEPTION)
                .addException(InvocationTargetException.class)
                .addException(IllegalAccessException.class);

        // method body
        invokeBuilder.addStatement("$T method = (Method) mapping.get(path + KEY_METHOD)", METHOD)
                .beginControlFlow("if (method == null) ")
                .addStatement("throw new $T(\"path\" + $S)", NOT_FOUND_PATH_EXCEPTION,
                        " path not found.")
                .endControlFlow()
                .addStatement("String args = (String) mapping.get(path + KEY_ARGS)")
                .addStatement("boolean empty = args == null || args.length() == 0")
                .beginControlFlow("if (!empty) ")
                .beginControlFlow("if (args.contains(\",\")) ")
                .addStatement("String[] names = args.split(\",\")")
                .addStatement("Object[] arr = new Object[names.length]")
                .beginControlFlow("for (int i = 0; i < names.length; i++)")
                .addStatement("arr[i] = params.get(names[i])")
                .endControlFlow()
                .addStatement("method.invoke(original, arr)")
                .addStatement("return")
                .endControlFlow()
                .addStatement("method.invoke(original,params.get(args))")
                .addStatement("return")
                .endControlFlow()
                .addStatement("method.invoke(original)");

        // constructor build
        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder();
        constructorBuilder.addModifiers(Modifier.PUBLIC)
                .addException(NoSuchMethodException.class)
                .addException(IllegalAccessException.class)
                .addException(InstantiationException.class);

        // constructor body
        ClassName original = ClassName.get(elementUtils.getPackageOf(moduleEle).toString(),
                moduleEle.getSimpleName().toString());
        constructorBuilder.addStatement("this.original = $T.class.newInstance()", original)
                .addStatement("this.mapping = new $T()", HashMap.class);

        // parse RouterPath
        int size = allEle.size();
        for (int i = 0; i < size; i++) {
            Element elm = allEle.get(i);
            RouterPath pathAnno = elm.getAnnotation(RouterPath.class);
            if (pathAnno == null)
                continue;

            String agrs = ((ExecutableElement) elm).getParameters().toString();
            String meyhodParams = elm.toString();
            int start = meyhodParams.indexOf("(");
            int end = meyhodParams.indexOf(")");
            if (end - start > 1) {
                // open1(java.lang.String,com.tangxiaolv.router.Promise) ==>
                // ,java.lang.String.class,com.tangxiaolv.router.Promise.class))
                meyhodParams = ","
                        + meyhodParams.substring(start + 1, end).replaceAll(",", ".class,")
                        + ".class))";
            } else {
                meyhodParams = "))";
            }

            String methodKey = pathAnno.value().toLowerCase();
            String methodName = elm.getSimpleName().toString();
            constructorBuilder.addStatement(
                    "mapping.put($S + KEY_METHOD, original.getClass().getMethod($S" + meyhodParams,
                    methodKey, methodName);
            constructorBuilder.addStatement("String args$L = $S", i, agrs);
            constructorBuilder.addStatement("mapping.put($S + KEY_ARGS, args$L)", methodKey, i)
                    .addCode("\n");
        }

        // check multi schemes
        String scheme_main = schemes.contains("|") ? schemes.split("\\|")[0] : schemes;
        // java file build
        String mirror_name_main = PREFIX + scheme_main + "_" + host;
        TypeSpec clazz = TypeSpec.classBuilder(mirror_name_main)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                // Fields
                .addFields(buildRouterModuleFields())
                // constructor
                .addMethod(constructorBuilder.build())
                // Methods
                .addMethod(invokeBuilder.build())
                .build();

        JavaFile javaFile = JavaFile.builder(PACKAGE_NAME, clazz).build();
        javaFiles.add(javaFile);

        if (!schemes.equals(scheme_main)) {
            makeSubFile(schemes, host, ClassName.get(PACKAGE_NAME, mirror_name_main), javaFiles);
        }
    }

    // build fields
    private Iterable<FieldSpec> buildRouterModuleFields() {
        ArrayList<FieldSpec> fieldSpecs = new ArrayList<>();
        FieldSpec f_mapping = FieldSpec.builder(HashMap.class, "mapping")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();
        fieldSpecs.add(f_mapping);

        FieldSpec f_original = FieldSpec.builder(Object.class, "original")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();
        fieldSpecs.add(f_original);

        FieldSpec f_KEY_METHOD = FieldSpec.builder(String.class, "KEY_METHOD")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", "_METHOD")
                .build();
        fieldSpecs.add(f_KEY_METHOD);

        FieldSpec f_KEY_ARGS = FieldSpec.builder(String.class, "KEY_ARGS")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", "_AGRS")
                .build();
        fieldSpecs.add(f_KEY_ARGS);

        return fieldSpecs;
    }

    // if has multi schemes. contains "|"
    private void makeSubFile(String scheme, String host, ClassName main, List<JavaFile> javaFiles) {
        String[] schemes = scheme.split("\\|");
        for (int i = 1; i < schemes.length; i++) {
            String subScheme = schemes[i];

            //filed build
            FieldSpec f_main = FieldSpec.builder(Object.class, "main")
                    .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                    .build();

            // constructor build
            MethodSpec.Builder constructor = MethodSpec.constructorBuilder();
            constructor.addModifiers(Modifier.PUBLIC)
                    .addException(IllegalAccessException.class)
                    .addException(InstantiationException.class)
                    .addStatement("this.main = $T.class.newInstance()", main);

            // method build
            MethodSpec.Builder invoke = MethodSpec.methodBuilder("invoke")
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .returns(void.class)
                    .addParameter(String.class, "path")
                    .addParameter(PARAMS_WRAPPER, "params")
                    .addException(NoSuchMethodException.class)
                    .addException(InvocationTargetException.class)
                    .addException(IllegalAccessException.class)
                    .addStatement(
                            "main.getClass().getMethod(\"invoke\",String.class,$T.class).invoke(main, path, params)", PARAMS_WRAPPER);

            // java file build
            String mirror_name_sub = PREFIX + subScheme + "_" + host;
            TypeSpec clazz = TypeSpec.classBuilder(mirror_name_sub)
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .addField(f_main)
                    // constructor
                    .addMethod(constructor.build())
                    // Methods
                    .addMethod(invoke.build())
                    .build();

            JavaFile javaFile = JavaFile.builder(PACKAGE_NAME, clazz).build();
            javaFiles.add(javaFile);
        }
    }
}