import com.branegy.service.core.QueryRequest
import com.branegy.inventory.api.ContactService
import com.branegy.inventory.api.InventoryService
import com.branegy.inventory.model.*
import java.util.Map.Entry
import io.dbmaster.tools.LdapSearch
import javax.naming.directory.BasicAttribute
import javax.naming.directory.SearchResult
import com.branegy.persistence.custom.BaseCustomEntity
import java.util.LinkedHashSet

import java.util.function.BiFunction
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

import java.util.stream.Stream
import static java.util.stream.Collectors.toCollection

import org.apache.commons.jexl3.JexlEngine
import org.apache.commons.jexl3.JexlBuilder
import org.apache.commons.jexl3.JexlExpression
import org.apache.commons.jexl3.JexlContext

logger.debug("Object ${p_object_type}")
logger.debug("Action ${p_action}")

def convertMapping = { mappingText ->
    JexlEngine jexl = new JexlBuilder().strict(false).debug(true).create();
    def jexlExps = [:]
    if (mappingText!=null && !mappingText.isEmpty()) {
        for (String row : mappingText.split("(\r\n|\n)")) {
            if (row.contains('=') && !row.trim().isEmpty()) {
                String[] kv = row.split("=",2);
                kv[0] = kv[0].trim();
                kv[1] = kv[1].trim();
                
                jexlExps[kv[0]] = kv[1].isEmpty() ? null : jexl.createScript( kv[1]);
            }
        }
    }
    return jexlExps;
}

class DnSuffixPatternList {
    private final List<String> includes = [];
    private final List<String> excludes = [];
    
    public DnSuffixPatternList(String config) {
        if (config == null || config.isEmpty()) {
            return;
        }
        for (String line:config.split("(\\s*[\\r\\n]+\\s*)+")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            if (line[0]=='-') {
                excludes.add(line.substring(1));
            } else if (line[0]=='+') {
                includes.add(line.substring(1));
            } else {
                logger.error("Expected starts with +/-: {}",line);
            }
        }
        
    }
    
    public boolean accept(SearchResult sr) {
        String dn = sr.getNameInNamespace();
        if (includes.isEmpty()) {
            return excludes.find{it-> dn.endsWith(it)} == null;
        } else {
            return includes.find{it-> dn.endsWith(it)} != null;
        }
    }
}

def dnPattern = new DnSuffixPatternList(p_distinguished_name_filter);

def show_new       = p_status_filter.contains("New");
def show_changed   = p_status_filter.contains("Changed");
def show_deleted   = p_status_filter.contains("Deleted");
def show_unchanged = p_status_filter.contains("Unchanged");

def set_inactive = p_delete_action == "Set Inactive";

def ldap = new LdapSearch(dbm, logger)
def mapping = convertMapping(p_attributes)
def go = p_action.equals("Import")

def attributes = mapping.values().stream()
                                 .flatMap({ exp -> 
                                       def vars = exp.getVariables();
                                       if (vars == null || vars.isEmpty()) {
                                           return Stream.empty();
                                       }
                                       return vars.stream().flatMap({varList -> 
                                            if (varList.isEmpty()) {
                                                return Stream.empty();
                                            } else {
                                                return varList.stream().limit(1);
                                            }
                                       });
                                 })
                                 .filter({ exp -> exp!=null})
                                 .collect(toCollection({new LinkedHashSet()})); 
attributes.add("name")
attributes.add("mail")
attributes.add("displayName")

logger.debug("Load attributes to load {}",attributes);

List<SearchResult> ldapObjects = ldap.search(p_server, p_base_context, p_ldap_query, attributes.join(";"))
ldapObjects = ldapObjects.findAll{ it -> dnPattern.accept(it) }

logger.info("Found ${ldapObjects.size()} objects")

def getValue = { attributeSet, attributeId ->
    def attrValues = attributeSet.get(attributeId)
    if (attrValues==null) {
        return null
    } else {
        if (attrValues.size()==1) {
            return attrValues.get()
        } else {
            def result = []
            def values = attrValues.getAll()
            while (values.hasMore()) {
                result.add(values.next())
            }
            return result
        }
    }
}

println """<script src="https://ajax.googleapis.com/ajax/libs/jquery/3.3.1/jquery.min.js" defer></script>"""
println """<script src="https://cdnjs.cloudflare.com/ajax/libs/floatthead/2.0.3/jquery.floatThead.min.js" defer></script>"""
def inlineScriptBase64 = """
\$(function(){
   \$('table.simple-table').floatThead();
});
""".bytes.encodeBase64();
println """<script src="data:text/javascript;base64,${inlineScriptBase64}" defer></script>"""
println """
<table class="simple-table" cellspacing="0">
    <thead>
    <tr style="background-color:#EEE">
        <td>Object Name</td>
        <td>Status</td>
        <td>Changes</td>
        <td>Ldap Distinguished Name</td>
    </tr>
    </thead><tbody>
""";

def printRow = { name, oldObject, newObject, ldapDn ->
    if (newObject!=null) {
        def it = newObject.values().iterator();
        while (it.hasNext()) {
            if (it.next() == null) {
                it.remove();
            }
        }
    }
    
    if (oldObject !=null && newObject!=null) {
        boolean equals = true;
        for (Entry e:newObject.entrySet()) {
            if (!e.key.equals("ServerName") && !e.value.equals(oldObject[e.key])) {
                equals = false;
                break;
            }
        }
        if (equals && !show_unchanged) {
            return;
        }
        
        print "<tr>"
        print "<td>${name}</td>"
        print "<td>${equals?"None":"Changed"}</td>"
        print "<td>"
            if (!equals) {
                newObject.each{ k,v2 ->
                    def v1 = oldObject.get(k);
                    if (v1!=null && v2!=null) {
                        if (!k.equals("ServerName") && !v1.equals(v2)) {
                            print "${k} changed from ${v1} to ${v2}</br>"
                        }
                    } else if (v1==null) {
                        print "${k} set to ${v2}</br>"
                    }
                }
                oldObject.each{ k,v1 ->
                    if (mapping.containsKey(k) && !newObject.containsKey(k) && !"LastSyncDate".equals(k)) {
                        print "${k} with value ${v1} was removed</br>"
                    }
                }
            }
        print "</td>"
	    print "<td>${ldapDn}</td>"
        print "</tr>"
    } else if (oldObject != null) {
        print "<tr>"
        print "<td>${name}</td>"
        print "<td>Deleted</td>"
        print "<td>"
            oldObject.each{ k,v1 ->
                if (!k.equals("ServerName")) { print "${k}: ${v1}</br>"; }
            }
        print "</td>"
	print "<td></td>"
        print "</tr>"
    } else {
        print "<tr>"
        print "<td>${name}</td>"
        print "<td>New</td>"
        print "<td>"
            newObject.each{ k,v2 ->
                if (!k.equals("ServerName")) { print "${k}: ${v2}</br>"; }
            }
        print "</td>"
	    print "<td>${ldapDn}</td>"
        print "</tr>"
    }
}

Date lastSyncDate = new Date();

/*def <T extends BaseCustomEntity> x =*/
def process = {List<?> list, 
               BiFunction<Map<String,Object>,Object,String> key,
               Function<Map<String,Object>> name, 
               Supplier<?> create,  
               Consumer<?> save, 
               Consumer<?> delete,
               Set<String> requiredFields
               -> 
    Map<String,?> inventoryObjects = list.collectEntries{[(key.apply(it.getCustomMap(),it).toUpperCase()): it]};
    
    ldapObjects.each{ ldapObject ->
        def attrs = ldapObject.getAttributes()
        logger.debug("{} {}", ldapObject, attrs)
        
        def oldProperties = null;
        def newProperties = [:];
        
        JexlContext ctx = new JexlContext() {
            public Object get(String _name) {
               return getValue(attrs,_name); 
            }
            
            public void set(String _name, Object value) {
                throw new UnsupportedOperationException("Not implemented");
            }
            
            public boolean has(String _name) {
                return getValue(attrs,_name)!=null;
            }
        };
        
        mapping.each { invAttribute, exp ->
            
            if (invAttribute == "DistinguishedName") {
                newProperties[invAttribute] = ldapObject.getNameInNamespace();
                return;
            }
            
            def result = exp.evaluate(ctx);
            logger.debug("{} eval as {}",invAttribute,result )
            newProperties[invAttribute] = result;
        }
            
        
        logger.debug("=> {}",newProperties)
        
        requiredFields.forEach({requiredField->
            if (!newProperties.containsKey(requiredField)) {
                throw new IllegalArgumentException("Field '"+requiredField+"' is not specified");
            } else if (newProperties[requiredField]==null) {
                throw new IllegalArgumentException("Field '"+requiredField+"' is null");
            }
        });
        
        def keyId = key.apply(newProperties,ldapObject) 
        if (keyId!=null) {
            def object = inventoryObjects.get(keyId.toString().toUpperCase())
            if (object==null) {
                if (!show_new) {
                    return;
                }
                
                object = create.get();
                object.setProject(dbm.getService(com.branegy.service.base.api.ProjectService.class).getCurrentProject())  // TODO: delete in 1.12
                logger.debug("Creating a new objects")
            } else {
                if (!show_changed) {
                    return;
                }
                
                oldProperties = object.getCustomMap();
            }
            
            printRow(name.apply(newProperties), oldProperties, newProperties, ldapObject.getNameInNamespace())
            
            if (go && save!=null) {
                newProperties["LastSyncDate"] = lastSyncDate;
                object.getCustomMap().putAll(newProperties);
                save.accept(object);
            }
            inventoryObjects.remove(keyId.toString().toUpperCase());
        }
    }
    
    if (show_deleted) {
        inventoryObjects.each{k, v ->
            printRow(k, v.getCustomMap(), null, null)
            if (go) {
                if (set_inactive && save!=null) {
                    v.setCustomData("Active", false);
                    save.accept(object);
                }
                if (!set_inactive && delete != null) {
                    delete.accept(v);
                }
            }
        }
    }
}

if ("Contacts".equals(p_object_type)) {
    def contactService = dbm.getService(ContactService.class)
    process(contactService.getContactList(new QueryRequest(p_object_filter)),
        { m,o -> m["ContactName"]},
        { m   -> m["ContactName"]},
        Contact.metaClass.&invokeConstructor,
        { o -> contactService.saveContact(o)},
        { o -> contactService.deleteContact(o.getId())},
        ["ContactName"] as Set,
    );
    
} else if ("Servers".equals(p_object_type)) {
    def inventoryService = dbm.getService(InventoryService.class)
    process(inventoryService.getServerList(new QueryRequest(p_object_filter)),
        { m,o -> m["ServerName"]},
        { m   -> m["ServerName"]},
        Server.metaClass.&invokeConstructor,
        { o -> inventoryService.saveServer(o)},
        { o -> inventoryService.deleteServer(o.getId())},
        ["ServerName"] as Set,
    );
    
} else if ("SecurityObjects".equals(p_object_type)) {
    def inventoryService = dbm.getService(InventoryService.class)
    process(inventoryService.getSecurityObjectList(new QueryRequest(p_object_filter)),
        { m,o -> m["Source"]+"/"+m["ServerName"]+"/"+m["Id"]},
        { m   -> m["Name"]},
        SecurityObject.metaClass.&invokeConstructor,
        { o -> inventoryService.saveSecurityObject(o)},
        { o -> inventoryService.deleteSecurityObject(o.getId())},
        ["Source","ServerName","Id","Name"] as Set,
    );
}

println """</tbody></table>""";