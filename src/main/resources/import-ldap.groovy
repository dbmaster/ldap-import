import com.branegy.service.core.QueryRequest
import com.branegy.inventory.api.ContactService
import com.branegy.inventory.api.InventoryService
import com.branegy.inventory.model.*
import java.util.Map.Entry
import io.dbmaster.tools.LdapSearch
import javax.naming.directory.BasicAttribute

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

def ldap = new LdapSearch(dbm, logger)
def mapping = convertMapping(p_attributes)
def go = p_action.equals("Import")

def attributes = mapping.values().collect() 
attributes.add("name")
attributes.add("mail")
attributes.add("displayName")
attributes.add("distinguishedName")

def ldapObjects = ldap.search(p_server, p_base_context, p_ldap_query, attributes.join(";"))
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
    </tr>
""";

def processRow = { name, oldObject, newObject ->
    if (newObject!=null) {
        def it = newObject.values().iterator();
        while (it.hasNext()) {
            if (it.next() == null) {
                it.remove();
            }
        }
    }
    
    print "<tr>"
    if (oldObject !=null && newObject!=null) {
        print "<td>${name}</td>"
        
        boolean equals = true;
        for (Entry e:newObject.entrySet()) {
            if (!e.value.equals(oldObject[e.key])) {
                equals = false;
                break;
            }
        }
        
        print "<td>${equals?"None":"Changed"}</td>"
        print "<td>"
            if (!equals) {
                newObject.each{ k,v2 ->
                    def v1 = oldObject.get(k);
                    if (v1!=null && v2!=null) {
                        if (!v1.equals(v2)) {
                            print "${k} changed from ${v1} to ${v2}</br>";
                        }
                    } else if (v1==null) {
                        print "${k} set to ${v2}</br>";
                    }
                }
                oldObject.each{ k,v1 ->
                    if (!newObject.containsKey(k) && !"LastSyncDate".equals(k)) {
                        print "${k} with ${v1} was removed</br>";
                    }
                }
            }
        print "</td>"
    } else if (oldObject != null) {
        print "<td>${name}</td>"
        print "<td>Deleted</td>"
        print "<td>"
            oldObject.each{ k,v1 ->
                print "${k} with ${v1} was removed</br>";
            }
        print "</td>"
    } else {
        print "<td>${name}</td>"
        print "<td>New</td>"
        print "<td>"
            newObject.each{ k,v2 ->
                print "${k} set to ${v2}</br>";
            }
        print "</td>"
    }
    print "</tr>"
}

Date lastSyncDate = new Date();

if ("Contacts".equals(p_object_type)) {
    def contactService = dbm.getService(ContactService.class)
    Map<String,Contact> inventoryContacts = contactService.getContactList(new QueryRequest(p_object_filter)).collectEntries{[(it.contactName): it]}
    
    Contact contact;
    ldapObjects.each { ldapObject ->
        def attrs = ldapObject.getAttributes()
        logger.debug("{}",ldapObject)
        
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
                logger.debug("Creating a new Contact")
            } else {
                oldProperties = contact.getCustomMap();
            }
            
            processRow(contactEmail, oldProperties, newProperties);
            
            if (go) {
                newProperties["LastSyncDate"] = lastSyncDate;
                contact.getCustomMap().putAll(newProperties);
                inventoryContacts.saveContact(contact);
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
    Map<String,Server> inventoryServers = inventoryService.getServerList(new QueryRequest(p_object_filter)).collectEntries{[(it.serverName): it]}
    
    Server server;
    ldapObjects.each { ldapObject ->
        def attrs = ldapObject.getAttributes()
        logger.debug("{}",ldapObject)
        
        def oldProperties = null;
        def newProperties = [:];
        mapping.each { invAttribute, ldapAttribute ->
            def value = getValue(attrs,ldapAttribute)
            newProperties[invAttribute] = value
        }
        newProperties["Source"] = p_source;
        
        def serverName = newProperties["ServerName"]
        if (serverName!=null) {
            server = inventoryServers.get(serverName)
            if (server==null) {
                server = new Server()
                logger.debug("Creating a new Server")
            } else {
                oldProperties = server.getCustomMap();
            }
            
            processRow(serverName, oldProperties, newProperties);
            
            if (go) {
                newProperties["LastSyncDate"] = lastSyncDate;
                server.getCustomMap().putAll(newProperties);
                inventoryService.saveServer(server);
            }
            inventoryServers.remove(serverName);
        }
    }
    inventoryServers.each{k,v ->
        processRow(k, v.getCustomMap(), null);
        if (go) {
            inventoryService.deleteServer(v.getId());
        }
    }
}

println """</table>""";

