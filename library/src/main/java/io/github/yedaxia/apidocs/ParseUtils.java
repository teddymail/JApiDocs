package io.github.yedaxia.apidocs;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import io.github.yedaxia.apidocs.parser.FieldNode;
import io.github.yedaxia.apidocs.parser.ResponseNode;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

/**
 * some util methods during parse
 *
 * @author yeguozhong yedaxia.github.com
 */
public class ParseUtils {

    /**
     * means a model class type
     */
    private static final String TYPE_MODEL = "unkown";

    /**
     * search File of className in the java file
     *
     * @param inJavaFile
     * @param className
     * @return
     */
    public static File searchJavaFile(File inJavaFile, String className){
        CompilationUnit compilationUnit = compilationUnit(inJavaFile);

        String[] cPaths;

        Optional<ImportDeclaration> idOp = compilationUnit.getImports()
                .stream()
                .filter(im -> im.getNameAsString().endsWith(className))
                .findFirst();

        //found in import
        if(idOp.isPresent()){
            cPaths = idOp.get().getNameAsString().split("\\.");
            return backTraceJavaFileByName(cPaths);
        }

        //inner class
        Optional<ClassOrInterfaceDeclaration> innerClassOp = getOpInnerClass(compilationUnit, className);

        if(innerClassOp.isPresent()){
            return inJavaFile;
        }

        cPaths = className.split("\\.");

        //current directory
        if(cPaths.length == 1){

            File[] javaFiles = inJavaFile.getParentFile().listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.equals(className + ".java");
                }
            });

            if(javaFiles != null && javaFiles.length == 1){
                return javaFiles[0];
            }

        }else{

            final String firstPath = cPaths[0];
            //same package inner class
            File[] javaFiles = inJavaFile.getParentFile().listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    int i = name.lastIndexOf(".java");
                    if(i == -1){
                        return false;
                    }
                    return name.substring(0, i).equals(firstPath);
                }
            });

            if(javaFiles != null && javaFiles.length > 0){
                File javaFile = javaFiles[0];
                if(getOpInnerClass(compilationUnit(javaFile), className).isPresent()){
                    return javaFile;
                }
            }
        }

        //maybe a complete class name
        File javaFile = backTraceJavaFileByName(cPaths);
        if(javaFile != null){
            return javaFile;
        }

        //.* at import
        NodeList<ImportDeclaration> importDeclarations = compilationUnit.getImports();
        if(importDeclarations.isNonEmpty()){
            for(ImportDeclaration importDeclaration : importDeclarations){
                if(importDeclaration.toString().contains(".*")){
                    String packageName = importDeclaration.getNameAsString();
                    cPaths = (packageName + "." + className).split("\\.");
                    javaFile = backTraceJavaFileByName(cPaths);
                    if(javaFile != null){
                        break;
                    }
                }
            }
        }

        if(javaFile == null){
            throw new RuntimeException("cannot find java file , in java file : " + inJavaFile.getAbsolutePath() + ", className : " +className);
        }

        return javaFile;
    }

    /**
     * get inner class declaration, it has bugs.
     *
     * @param compilationUnit
     * @param className
     * @return
     */
    private static Optional<ClassOrInterfaceDeclaration> getOpInnerClass(CompilationUnit compilationUnit ,String className){
        return compilationUnit.getChildNodesByType(ClassOrInterfaceDeclaration.class)
                .stream()
                .filter(c -> className.endsWith(c.getNameAsString()))
                .findFirst();
    }

    private static File backTraceJavaFileByName(String[] cPaths){
        if(cPaths.length == 0){
            return null;
        }
        String javaFilePath = DocContext.getJavaSrcPath() + Utils.joinArrayString(cPaths, "/") +".java";
        File javaFile = new File(javaFilePath);
        if(javaFile.exists() && javaFile.isFile()){
            return javaFile;
        }else{
            return backTraceJavaFileByName(Arrays.copyOf(cPaths, cPaths.length - 1));
        }
    }

    /**
     * get java file parser object
     *
     * @param javaFile
     * @return
     */
    public static CompilationUnit compilationUnit(File javaFile){
        try{
            return JavaParser.parse(javaFile);
        }catch (FileNotFoundException e){
            throw new RuntimeException("java file not exits , file path : " + javaFile.getAbsolutePath());
        }
    }

    /**
     * parse response of model java file
     *
     * @param modelJavaFile
     * @param responseNode
     */
    public static void parseResponseNode(File modelJavaFile, ResponseNode responseNode){
        String resultClassName = responseNode.getClassName();

        ParseUtils.compilationUnit(modelJavaFile).
                getChildNodesByType(ClassOrInterfaceDeclaration.class).
                stream().filter(f -> resultClassName.endsWith(f.getNameAsString())).findFirst().ifPresent(cl -> {

                    NodeList<ClassOrInterfaceType> exClassTypeList =  cl.getExtendedTypes();
                    if(!exClassTypeList.isEmpty()){
                        String extendClassName = exClassTypeList.get(0).getNameAsString();
                        responseNode.setClassName(extendClassName);
                        parseResponseNode(ParseUtils.searchJavaFile(modelJavaFile, extendClassName), responseNode);
                    }

                    cl.getChildNodesByType(FieldDeclaration.class)
                    .stream().filter(fd -> !fd.getModifiers().contains(Modifier.STATIC))
                    .forEach(fd -> {

                        //内部类字段也会读取到，这里特殊处理
                        ClassOrInterfaceDeclaration cClDeclaration = (ClassOrInterfaceDeclaration)fd.getParentNode().get();
                        if(!resultClassName.endsWith(cClDeclaration.getNameAsString())){
                            return;
                        }

                        fd.getVariables().forEach(v -> {
                            FieldNode fieldNode = new FieldNode();
                            responseNode.addChildNode(fieldNode);
                            fd.getComment().ifPresent(c -> fieldNode.setDescription(c.getContent()));
                            fieldNode.setName(v.getNameAsString());
                            Type elType = fd.getElementType();
                            String type = elType.asString();
                            if(elType.getParentNode().get() instanceof ArrayType){
                                parseChildResponseNode(fieldNode, modelJavaFile, type, Boolean.TRUE);
                            }else{
                                if(isCollectionType(type)){
                                    elType.getChildNodesByType(ClassOrInterfaceType.class)
                                            .stream()
                                            .findFirst()
                                            .ifPresent(t ->{
                                                String genericType = t.getNameAsString();
                                                parseChildResponseNode(fieldNode, modelJavaFile, genericType, Boolean.TRUE);
                                            });
                                }else{
                                    parseChildResponseNode(fieldNode, modelJavaFile, type, Boolean.FALSE);
                                }
                            }
                        });
                    });
        });

        //恢复原来的名称
        responseNode.setClassName(resultClassName);
    }

    private static void parseChildResponseNode(FieldNode parentNode, File inJavaFile, String type, Boolean isList){
        String unifyType = unifyType(type);
        if(TYPE_MODEL.equals(unifyType)){
            ResponseNode childResponse = new ResponseNode();
            parentNode.setChildResponseNode(childResponse);
            childResponse.setList(isList);
            parentNode.setType(isList ? type + "[]" : type);
            childResponse.setClassName(type);
            parseResponseNode(searchJavaFile(inJavaFile, type), childResponse);
        } else {
            parentNode.setType(isList ? unifyType + "[]" : unifyType);
        }
    }

    /**
     * unify the type show in docs
     *
     * @param className
     * @return
     */
    public static String unifyType(String className){
        String[] cPaths = className.split("\\.");
        String rawType = cPaths[cPaths.length - 1];
        if("byte".equalsIgnoreCase(rawType)){
            return "byte";
        } else if("short".equalsIgnoreCase(rawType)){
            return "short";
        } else if("int".equals(rawType) || "Integer".equals(rawType)){
            return "int";
        } else if("long".equalsIgnoreCase(rawType)){
            return "long";
        } else if("float".equalsIgnoreCase(rawType)){
            return "float";
        } else if("double".equalsIgnoreCase(rawType)){
            return "double";
        } else if("boolean".equalsIgnoreCase(rawType)){
            return "boolean";
        } else if("String".equalsIgnoreCase(rawType)){
            return "string";
        } else if("date".equalsIgnoreCase(rawType)){
            return "date";
        } else if("file".equalsIgnoreCase(rawType)){
            return "file";
        } else{
            return TYPE_MODEL;
        }
    }

    private static boolean isCollectionType(String className){
        String[] cPaths = className.split("\\.");
        String genericType = cPaths[cPaths.length - 1];
        int genericLeftIndex = genericType.indexOf("<");
        String rawType = genericLeftIndex != -1 ? genericType.substring(0, genericLeftIndex) : genericType;
        String collectionClassName = "java.util."+rawType;
        try{
            Class collectionClass = Class.forName(collectionClassName);
            return Collection.class.isAssignableFrom(collectionClass);
        }catch (ClassNotFoundException e){
            return false;
        }
    }
}
