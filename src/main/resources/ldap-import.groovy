import com.branegy.service.core.QueryRequest
import com.branegy.inventory.api.ContactService
import com.branegy.inventory.api.InventoryService
import com.branegy.inventory.model.*
import java.util.Map.Entry
import io.dbmaster.tools.LdapSearch
import javax.naming.directory.BasicAttribute
import javax.naming.directory.SearchResult

logger.debug("Object ${p_object_type}")
logger.debug("Action ${p_action}")

def convertMapping = { mappingText ->
    def m = [:]
    if (mappingText!=null && !mappingText.isEmpty()) {
        for (String row : mappingText.split("(\r\n|\n)")) {
            if (row.contains('=') && !row.trim().isEmpty()) {
                String[] kv = row.split("=",2);
                logger.debug("m=${m}  ${kv[0]} ${kv[1]} " )
                m[kv[0]] = kv[1].isEmpty() ? null : kv[1];
            }
        }
    }
    return m
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

def ldap = new LdapSearch(dbm, logger)
def mapping = convertMapping(p_attributes)
def go = p_action.equals("Import")

def attributes = mapping.values().collect() 
attributes.add("name")
attributes.add("mail")
attributes.add("displayName")

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
            def values = value.getAll()
            while (values.hasMore()) {
                result.add(values.next())
            }
            return result
        }
    }
}

println """
<table class="simple-table" cellspacing="0">
    <tr>
        <td>Object Name</td>
        <td>Status</td>
        <td>Changes</td>
        <td>Ldap Distinguished Name</td>
    </tr>
""";

def processRow = { name, oldObject, newObject, ldapDn ->
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
        if (equals && !p_include_none) {
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

if ("Contacts".equals(p_object_type)) {
    def contactService = dbm.getService(ContactService.class)
    Map<String,Contact> inventoryContacts = contactService.getContactList(new QueryRequest(p_object_filter)).collectEntries{[(it.contactName): it]}
    
    Contact contact;
    ldapObjects.each { ldapObject ->
        def attrs = ldapObject.getAttributes()
        logger.debug("{}", ldapObject)
        
        def oldProperties = null;
        def newProperties = [:];
        mapping.each { invAttribute, ldapAttribute ->
            def value = getValue(attrs,ldapAttribute)
            newProperties[invAttribute] = value
        }
        newProperties["Source"] = p_source;
        
        def contactEmail = newProperties["ContactEmail"]
        if (contactEmail!=null) {
            contact = inventoryContacts.get(contactEmail)
            if (contact==null) {
                contact = new Contact()
                contact.setProject(dbm.getService(com.branegy.service.base.api.ProjectService.class).getCurrentProject()); // TODO: delete in 1.12
                logger.debug("Creating a new Contact")
            } else {
                oldProperties = contact.getCustomMap();
            }
            
            processRow(contactEmail, oldProperties, newProperties, ldapObject.getNameInNamespace());
            
            if (go) {
                newProperties["LastSyncDate"] = lastSyncDate;
                contact.getCustomMap().putAll(newProperties);
                contactService.saveContact(contact);
            }
            inventoryContacts.remove(contactEmail);
        }
    }
    /*inventoryContacts.each{k,v ->
        processRow(k, v.getCustomMap(), null);
        if (go) {
            contactService.deleteContact(v.getId());
        }
    }*/
} else if ("Servers".equals(p_object_type)) {
    def inventoryService = dbm.getService(InventoryService.class)
    Map<String,Server> inventoryServers = inventoryService.getServerList(new QueryRequest(p_object_filter)).collectEntries{[(it.serverName.toUpperCase()): it]}
    
    Server server;
    ldapObjects.each { ldapObject ->
        def attrs = ldapObject.getAttributes()
        logger.debug("{}", ldapObject)
        
        def oldProperties = null;
        def newProperties = [:];
        mapping.each { invAttribute, ldapAttribute ->
            def value = getValue(attrs,ldapAttribute)
            newProperties[invAttribute] = value
        }
        newProperties["Source"] = p_source;
        
        def serverName = newProperties["ServerName"]
        if (serverName!=null) {
            server = inventoryServers.get(serverName.toUpperCase())
            if (server==null) {
                server = new Server()
                server.setProject(dbm.getService(com.branegy.service.base.api.ProjectService.class).getCurrentProject())  // TODO: delete in 1.12
                logger.debug("Creating a new Server")
            } else {
                oldProperties = server.getCustomMap();
            }
            
            processRow(serverName, oldProperties, newProperties, ldapObject.getNameInNamespace())
            
            if (go) {
                newProperties["LastSyncDate"] = lastSyncDate;
                server.getCustomMap().putAll(newProperties);
                inventoryService.saveServer(server);
            }
            inventoryServers.remove(serverName.toUpperCase());
        }
    }
    inventoryServers.each{k, v ->
        processRow(k, v.getCustomMap(), null, null)
        if (go) {
            inventoryService.deleteServer(v.getId())
        }
    }
}

println """</table>""";