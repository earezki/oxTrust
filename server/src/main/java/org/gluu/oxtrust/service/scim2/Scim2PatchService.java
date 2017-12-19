package org.gluu.oxtrust.service.scim2;

import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.commons.lang.StringUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.gluu.oxtrust.model.exception.SCIMException;
import org.gluu.oxtrust.model.scim2.AttributeDefinition;
import org.gluu.oxtrust.model.scim2.BaseScimResource;
import org.gluu.oxtrust.model.scim2.annotations.Attribute;
import org.gluu.oxtrust.model.scim2.extensions.Extension;
import org.gluu.oxtrust.model.scim2.patch.PatchOperation;
import org.gluu.oxtrust.model.scim2.patch.PatchOperationType;
import org.gluu.oxtrust.model.scim2.util.IntrospectUtil;
import org.gluu.oxtrust.model.scim2.util.ScimResourceUtil;
import org.gluu.oxtrust.service.antlr.scimFilter.ScimFilterParserService;
import org.gluu.oxtrust.service.antlr.scimFilter.util.FilterUtil;
import org.slf4j.Logger;
import org.xdi.util.Pair;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.inject.Named;
import javax.lang.model.type.NullType;
import java.util.*;

/**
 * Created by jgomer on 2017-12-17.
 */
@Stateless
@Named
public class Scim2PatchService {

    @Inject
    private Logger log;

    @Inject
    private ScimFilterParserService filterService;

    @Inject
    private ExtensionService extService;

    public BaseScimResource applyPatchOperation(BaseScimResource resource, PatchOperation operation) throws Exception{

        BaseScimResource result=null;
        Map<String, Object> genericMap=null;
        PatchOperationType opType=operation.getType();
        Class<? extends BaseScimResource> clazz=resource.getClass();
        String path = operation.getPath();

        log.debug("applyPatchOperation of type {}", opType);

        //Determine if operation is with value filter
        if (StringUtils.isNotEmpty(path) && !operation.getType().equals(PatchOperationType.ADD)) {
            Pair<Boolean, String> pair = validateBracketedPath(path);

            if (pair.getFirst()) {
                String valSelFilter = pair.getSecond();
                if (valSelFilter == null)
                    throw new SCIMException("Unexpected syntax in value selection filter");
                else {
                    //Abort earlier

                    return applyPatchOperationWithValueFilter(resource, operation, valSelFilter);
                }
            }
        }

        if (!opType.equals(PatchOperationType.REMOVE)) {
            Object value = operation.getValue();
            List<String> extensionUrns=extService.getUrnsOfExtensions(clazz);

            if (value instanceof Map)
                genericMap = (Map<String, Object>) value;
            else{
                //It's an atomic value or an array
                if (StringUtils.isEmpty(path))
                    throw new SCIMException("Value(s) supplied for resource not parseable");

                //Create a simple map and trim the last part of path
                String subPaths[] = ScimResourceUtil.splitPath(path, extensionUrns);
                genericMap = Collections.singletonMap(subPaths[subPaths.length - 1], value);

                if (subPaths.length == 1)
                    path = "";
                else
                    path = path.substring(0, path.lastIndexOf("."));
            }

            if (StringUtils.isNotEmpty(path)){
                //Visit backwards creating a composite map
                String subPaths[] = ScimResourceUtil.splitPath(path, extensionUrns);
                for (int i = subPaths.length - 1; i >= 0; i--) {

                    //Create a string consisting of all subpaths until the i-th
                    StringBuilder sb=new StringBuilder();
                    for (int j=0;j<=i;j++)
                        sb.append(subPaths[j]).append(".");

                    Attribute annot = IntrospectUtil.getFieldAnnotation(sb.substring(0, sb.length()-1), clazz, Attribute.class);
                    boolean multivalued=!(annot==null || annot.multiValueClass().equals(NullType.class));

                    Map<String, Object> genericBiggerMap = new HashMap<String, Object>();
                    genericBiggerMap.put(subPaths[i], multivalued ? Collections.singletonList(genericMap) : genericMap);
                    genericMap = genericBiggerMap;
                }
            }

            log.debug("applyPatchOperation. Generating a ScimResource from generic map: {}", genericMap.toString());
        }

        //Try parse genericMap as an instance of the resource
        ObjectMapper mapper = new ObjectMapper();
        BaseScimResource alter=opType.equals(PatchOperationType.REMOVE) ? resource : mapper.convertValue(genericMap, clazz);
        List<Extension> extensions=extService.getResourceExtensions(clazz);

        switch (operation.getType()){
            case REPLACE:
                result=ScimResourceUtil.transferToResourceReplace(alter, resource, extensions);
                break;
            case ADD:
                result=ScimResourceUtil.transferToResourceAdd(alter, resource, extensions);
                break;
            case REMOVE:
                result=ScimResourceUtil.deleteFromResource(alter, operation.getPath(), extensions);
                break;
        }
        return result;

    }

    private BaseScimResource applyPatchOperationWithValueFilter(BaseScimResource resource, PatchOperation operation, String valSelFilter) throws SCIMException{

        String path=operation.getPath();
        int i=path.indexOf("[");
        String attribute=path.substring(0, i);

        i=path.lastIndexOf("].");
        String subAttribute= i==-1 ? "" : path.substring(i+2);

        ObjectMapper mapper=new ObjectMapper();
        Class<? extends BaseScimResource> cls=resource.getClass();
        Map<String, Object> resourceAsMap=mapper.convertValue(resource, new TypeReference<Map<String, Object>>(){});
        List<Map<String, Object>> list;

        Attribute attrAnnot=IntrospectUtil.getFieldAnnotation(attribute, cls, Attribute.class);
        if (attrAnnot!=null){
            if (!attrAnnot.multiValueClass().equals(NullType.class) && attrAnnot.type().equals(AttributeDefinition.Type.COMPLEX)){
                Object colObject=resourceAsMap.get(attribute);
                list = colObject==null ? null : new ArrayList<Map<String, Object>>((Collection<Map<String, Object>>) colObject);
            }
            else
                throw new SCIMException(String.format("Attribute '%s' expected to be complex multi-valued", attribute));
        }
        else
            throw new SCIMException(String.format("Attribute '%s' not recognized or expected to be complex multi-valued", attribute));

        if (list==null)
            log.info("applyPatchOperationWithValueFilter. List of values for {} is empty. Operation has no effect", attribute);
        else{
            try {
                valSelFilter = FilterUtil.preprocess(valSelFilter, cls, extService.getUrnsOfExtensions(cls));
                ParseTree parseTree = filterService.getParseTree(valSelFilter);

                List<Integer> matchingIndexes=new ArrayList<Integer>();
                for (i=0;i<list.size();i++){
                    if (filterService.complexAttributeMatch(parseTree, list.get(i), attribute, cls))
                        matchingIndexes.add(0, i);  //Important: add so that resulting list is reverse-ordered
                }
                /*
                Here we differ from spec (see section 3.5.2.3/4 of RFC7644. If no record match is made, we are supposed to
                return error 400 with scimType of noTarget. But this is clearly inconvenient
                */
                log.info("There are {} entries matching the filter '{}'", matchingIndexes.size(), path);

                for (Integer index : matchingIndexes){
                    if (operation.getType().equals(PatchOperationType.REMOVE)) {
                        if (subAttribute.length()==0)   //Remove the whole item
                            list.remove(index.intValue());      //If intValue is not used, the remove(Object) method is called!
                        else    //remove subattribute only
                            list.get(index).remove(subAttribute);
                    }
                    else{
                        if (subAttribute.length()==0)    //Updates all the item
                            list.set(index, (Map<String, Object>) operation.getValue());
                        else   //Updates a subattribute only
                            list.get(index).put(subAttribute, operation.getValue());
                    }
                }

                log.trace("New {} list is:\n{}", attribute, mapper.writeValueAsString(list));
                resourceAsMap.put(attribute, list.size()==0 ? null : list);
                resource=mapper.convertValue(resourceAsMap, cls);
            }
            catch (Exception e){
                log.info("Error processing Patch operation with value selection path '{}'", path);
                log.error(e.getMessage(), e);
                throw new SCIMException(e.getMessage(), e);
            }
        }
        return resource;

    }

    /**
     * It tries to determine if this is a valid path in terms of PATCH operation for the case of value selection filter.
     * Example: emails[value co ".com"]
     * @param path
     * @return
     */
    private Pair<Boolean, String> validateBracketedPath(String path){

        boolean isFilterExpression;
        String selFilter=null;

        int lBracketIndex = path.indexOf("[");
        //Check if characters preceding bracket look like attribute name
        isFilterExpression=(lBracketIndex>0) && path.substring(0, lBracketIndex).matches("[a-zA-Z]\\w*");

        if (isFilterExpression){
            int rBracketIndex = path.lastIndexOf("]");
            int lenm1=path.length()-1;
            /*
            It will be valid if character ] is the last of string, or if it is followed by a dot and at least one
            letter (thus specifying a subattribute name). Examples:
             - emails[type eq null]
             - addresses[value co "any[...]thing"]
             - ims[value eq "hi"].primary
             */
            if ((rBracketIndex>lBracketIndex) && (rBracketIndex==lenm1 ||
                    (lenm1-rBracketIndex>1 && path.charAt(rBracketIndex+1)=='.' && Character.isLetter(path.charAt(rBracketIndex+2)))))
                selFilter=path.substring(lBracketIndex+1, rBracketIndex);

        }
        return new Pair<Boolean, String>(isFilterExpression, selFilter);

    }

}