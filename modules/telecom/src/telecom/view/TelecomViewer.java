package telecom.view;

import java.util.List;

import rescuecore2.standard.view.AnimatedWorldModelViewer;
import rescuecore2.view.ViewLayer;

/**
   A viewer for StandardWorldModels plus the telecom module's extra layers
   (BTS coverage discs). The standard layers come from
   {@link AnimatedWorldModelViewer#addDefaultLayers()}; the telecom layers
   are appended on top. Non-telecom scenarios render identically to the
   standard animated viewer because BTSLayer draws nothing when the
   TelecomRegistry is empty.
 */
public class TelecomViewer extends AnimatedWorldModelViewer {
    @Override
    public void addDefaultLayers() {
        super.addDefaultLayers();
        addLayer(new BTSLayer());
    }

    @Override
    public String getViewerName() {
        return "Telecom viewer";
    }

    /** Test hook: the installed layer list (getLayers is protected). */
    List<ViewLayer> layersForTest() {
        return getLayers();
    }
}
