import com.branegy.service.core.QueryRequest
import com.branegy.inventory.api.ContactService
import com.branegy.inventory.model.*
import io.dbmaster.tools.LdapSearch
import javax.naming.directory.BasicAttribute

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

def attributes = mapping.values().collect() 
attributes.add("name")
attributes.add("mail")
attributes.add("displayName")

def ldapObjects = ldap.search(p_server, p_base_context, p_ldap_query, attributes.join(";"))
def contactService = dbm.getService(ContactService.class)
def inventoryContacts = contactService.getContactList(new QueryRequest())

Contact contact;
def go = p_action.equals("Import")

logger.debug("Action ${p_action}")
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

ldapObjects.each { ldapContact ->
    def attrs = ldapContact.getAttributes()
    def mail  = getValue(attrs, "mail")

    // logger.info("mail = ${ldapContact.mail} all ${ldapContact}")
    if (mail!=null) {
        contact = inventoryContacts.find { it.getCustomData("ContactEmail") == mail }
        if (contact==null) {
            contact = new Contact()
            logger.debug("Creating a new contact")
        }
        contact.setContactName(getValue(attrs,"displayName")?:getValue(attrs,"name"))
        mapping.each { invAttribute, ldapAttribute ->
            def value = getValue(attrs,ldapAttribute)
            if (go) {
                contact.setCustomData(invAttribute, value)
            }
            // logger.debug("Setting ${invAttribute} to ${value} attr: ${ldapAttribute}")
        }
        logger.debug("Contact id = ${contact.id}")
        if (contact.id==0 && go) {
            contactService.createContact(contact)
        }
    } 
}