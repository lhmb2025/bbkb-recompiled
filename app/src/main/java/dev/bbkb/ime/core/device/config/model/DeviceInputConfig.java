package dev.bbkb.ime.core.device.config.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Container class for device input mapping configuration.
 */
public class DeviceInputConfig {
    
    public List<DeviceInputMapping> mappings;
    
    public DeviceInputConfig() {
        this.mappings = new ArrayList<>();
    }
    
    /**
     * Find a mapping for the specified device name.
     * @param deviceName The name of the input device
     * @return DeviceInputMapping if found, null otherwise
     */
    public DeviceInputMapping findMappingForDevice(String deviceName) {
        return findMappingForDevice(deviceName, null, null);
    }
    
    /**
     * Find a mapping for the specified device with full matching criteria.
     * @param deviceName The name of the input device
     * @param vendorId Optional vendor ID (hex string)
     * @param productId Optional product ID (hex string)
     * @return DeviceInputMapping if found, null otherwise
     */
    public DeviceInputMapping findMappingForDevice(String deviceName, String vendorId, String productId) {
        if (deviceName == null || deviceName.isEmpty()) {
            return null;
        }
        
        for (DeviceInputMapping mapping : mappings) {
            // Use the proper matchesDevice method that supports matchCriteria
            if (mapping.matchesDevice(deviceName, vendorId, productId)) {
                return mapping;
            }
        }
        
        return null;
    }
    
}
