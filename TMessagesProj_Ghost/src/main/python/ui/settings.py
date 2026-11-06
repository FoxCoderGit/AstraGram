from dataclasses import dataclass, field


@dataclass
class Switch:
    key: str
    text: str
    default: bool
    subtext: str = None
    icon: str = None
    on_change: object = field(default=None, compare=False, repr=False)
    type: str = field(default='switch', init=False)


@dataclass
class Selector:
    key: str
    text: str
    default: int
    items: list
    icon: str = None
    on_change: object = field(default=None, compare=False, repr=False)
    type: str = field(default='selector', init=False)


@dataclass
class Input:
    key: str
    text: str
    default: str = ''
    subtext: str = None
    icon: str = None
    on_change: object = field(default=None, compare=False, repr=False)
    type: str = field(default='input', init=False)


@dataclass
class Text:
    text: str
    icon: str = None
    accent: bool = False
    red: bool = False
    on_click: object = field(default=None, compare=False, repr=False)
    create_sub_fragment: object = field(default=None, compare=False, repr=False)
    type: str = field(default='text', init=False)


@dataclass
class Header:
    text: str
    type: str = field(default='header', init=False)


@dataclass
class Divider:
    text: str = None
    type: str = field(default='divider', init=False)


# Newer plugin extensions remain explicit additions to the six reference types.
@dataclass
class EditText:
    key: str = ''
    hint: str = ''
    default: str = ''
    multiline: bool = False
    max_length: int = 0
    mask: str = ''
    on_change: object = None
    text: str = ''


@dataclass
class Custom:
    item: object = None
    view: object = None
    factory: object = None
    factory_args: tuple = field(default_factory=tuple)
    create_view: object = None
    bind_view: object = None
    on_click: object = None
    on_long_click: object = None
    create_sub_fragment: object = None
    link_alias: str = ""

    def __post_init__(self):
        factory = self.factory
        if factory is None: return
        if self.item is None and callable(getattr(factory, 'create_item', None)):
            self.item = factory.create_item(None, self, self.factory_args or ())
        for name in ('create_view', 'bind_view', 'on_click', 'on_long_click'):
            if getattr(self, name) is None: setattr(self, name, getattr(factory, name, None))


class _FactoryJavaShim:
    """Small compatibility object for plugins which retain a Java factory.

    GhostGram renders these rows itself, but ExteraGram plugins use
    ``factory.instance.java.viewType`` while constructing their definitions.
    """
    viewType = 0


class SimpleSettingFactory:
    """Import-compatible wrapper for plugins using ExteraGram custom rows.

    GhostGram's list renderer cannot host arbitrary Python-created Android views
    yet, but keeping their metadata lets those plugins load and use the rest of
    their settings instead of failing during import.
    """
    def __init__(self, create_view=None, bind_view=None, is_clickable=False,
                 is_shadow=False, create_item=None, on_click=None,
                 on_long_click=None, **_unused):
        self.create_view = create_view
        self.bind_view = bind_view
        self.is_clickable = is_clickable
        self.is_shadow = is_shadow
        self.create_item = create_item
        self.on_click = on_click
        self.on_long_click = on_long_click
        self.instance = self
        self.java = self
        self.viewType = 0

    def __call__(self, *args, **kwargs):
        # ExteraGram factories may create a backing item separately from its
        # Android view. Preserve that item and callback metadata for the host
        # renderer; unsupported display-specific arguments are intentionally
        # kept out of the dataclass constructor.
        item = kwargs.pop("item", None)
        return Custom(
            item=item,
            factory=self,
            factory_args=args,
            create_view=kwargs.pop("create_view", self.create_view),
            bind_view=kwargs.pop("bind_view", self.bind_view),
            on_click=kwargs.pop("on_click", self.on_click),
            on_long_click=kwargs.pop("on_long_click", self.on_long_click),
            create_sub_fragment=kwargs.pop("create_sub_fragment", None),
            link_alias=kwargs.pop("link_alias", ""),
        )
