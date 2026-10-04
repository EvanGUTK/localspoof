#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RadioOwnership {
    initial: bool,
    expected: bool,
    changed: bool,
    overridden: bool,
}

impl RadioOwnership {
    pub fn new(initial: bool) -> Self {
        Self {
            initial,
            expected: initial,
            changed: false,
            overridden: false,
        }
    }
    pub fn initial(&self) -> bool {
        self.initial
    }
    pub fn applied(&mut self, enabled: bool) {
        self.expected = enabled;
        self.changed |= enabled != self.initial;
    }
    pub fn observe(&mut self, enabled: bool) {
        if enabled != self.expected {
            self.overridden = true;
        }
    }
    pub fn desired_restore(&self) -> Option<bool> {
        if self.changed && !self.overridden && !self.initial {
            Some(false)
        } else {
            None
        }
    }
}
